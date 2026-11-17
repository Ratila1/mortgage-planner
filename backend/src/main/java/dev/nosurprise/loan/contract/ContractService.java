package dev.nosurprise.loan.contract;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.outbox.ProcessedEvents;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.type.LoanTypeService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Генерация договора после одобрения (MICROLOANS.md §3.10, §4.8). GridFS не участвует в транзакции Postgres,
 * поэтому порядок такой: PDF → GridFS → транзакция Postgres (loan_documents + loan.contract.ready).
 * Уведомление клиенту уходит по loan.contract.ready, когда договор уже есть: иначе ссылка давала бы 404.
 */
@Service
public class ContractService {

    static final String CONSUMER = "loan.contract";
    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final LoanApplicationRepository applications;
    private final LoanDocumentRepository documents;
    private final LoanTypeService loanTypes;
    private final ContractRenderer renderer;
    private final GridFsTemplate gridFs;
    private final OutboxPublisher outbox;
    private final ProcessedEvents processed;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public ContractService(LoanApplicationRepository applications, LoanDocumentRepository documents,
                           LoanTypeService loanTypes, ContractRenderer renderer, GridFsTemplate gridFs,
                           OutboxPublisher outbox, ProcessedEvents processed,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.applications = applications;
        this.documents = documents;
        this.loanTypes = loanTypes;
        this.renderer = renderer;
        this.gridFs = gridFs;
        this.outbox = outbox;
        this.processed = processed;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    public void onApproved(LoanApplicationEvent event) {
        ContractData data = readTx.execute(status -> prepare(event.applicationId()));
        if (data == null) {
            return;
        }
        byte[] pdf = renderer.render(data);
        String sha256 = sha256(pdf);
        String fileName = fileName(event.applicationId());
        Document metadata = new Document("applicationId", event.applicationId().toString())
                .append("userId", event.userId().toString());
        ObjectId fileId = gridFs.store(new ByteArrayInputStream(pdf), fileName, "application/pdf", metadata);
        try {
            tx.executeWithoutResult(status -> {
                if (!processed.markProcessed(CONSUMER, event.eventId())) {
                    return;
                }
                Instant now = clock.instant();
                LoanDocument document = new LoanDocument(event.applicationId(), fileId.toHexString(), sha256, now);
                documents.saveAndFlush(document);
                outbox.publish(LoanRoutingKeys.CONTRACT_READY, LoanRoutingKeys.AGGREGATE, event.applicationId(),
                        new LoanApplicationEvent(UUID.randomUUID(), now, event.applicationId(), event.userId(),
                                LoanApplicationEvent.CONTRACT_READY));
            });
        } catch (DataIntegrityViolationException ex) {
            // Параллельная доставка уже сохранила договор (unique application_id, type): наш файл лишний.
            gridFs.delete(Query.query(Criteria.where("_id").is(fileId)));
            log.info("contract for application {} already exists, duplicate removed", event.applicationId());
            return;
        }
        // Чистим сироты от прошлых попыток (упали между GridFS и коммитом). Только после коммита и кроме своего файла:
        // так параллельная доставка не удалит файл, на который уже ссылается loan_documents.
        LoanDocument saved = documents.findByApplicationIdAndType(event.applicationId(), LoanDocument.TYPE_CONTRACT)
                .orElse(null);
        if (saved != null) {
            gridFs.delete(Query.query(Criteria.where("filename").is(fileName)
                    .and("_id").ne(new ObjectId(saved.getGridfsId()))));
        }
    }

    /** null — договор не нужен: заявка уже не в APPROVED или договор уже есть (повторная доставка). */
    private ContractData prepare(UUID applicationId) {
        LoanApplication application = applications.findById(applicationId).orElse(null);
        if (application == null || application.getStatus() != LoanStatus.APPROVED) {
            log.info("application {} is not approved anymore, contract skipped", applicationId);
            return null;
        }
        if (documents.findByApplicationIdAndType(applicationId, LoanDocument.TYPE_CONTRACT).isPresent()) {
            return null;
        }
        var applicant = application.getApplicant();
        var terms = application.getTerms();
        String typeName = loanTypes.get(application.getLoanTypeId()).getName();
        return new ContractData(applicationId, typeName, applicant.getLastName(), applicant.getFirstName(),
                applicant.getMiddleName(), applicant.getPhone(), application.getAmount(), application.getTermDays(),
                terms.getDailyRate(), terms.getInterest(), terms.getModifier(), terms.getTotal(),
                application.getDecidedAt(), application.getOfferExpiresAt());
    }

    /** PDF договора из GridFS. */
    public Resource load(LoanDocument document) {
        var file = gridFs.findOne(Query.query(Criteria.where("_id").is(new ObjectId(document.getGridfsId()))));
        if (file == null) {
            throw new DocumentNotFoundException("contract file not found");
        }
        return gridFs.getResource(file);
    }

    static String fileName(UUID applicationId) {
        return "contract-" + applicationId + ".pdf";
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
