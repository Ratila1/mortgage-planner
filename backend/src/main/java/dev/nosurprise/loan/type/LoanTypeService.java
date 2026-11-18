package dev.nosurprise.loan.type;

import dev.nosurprise.loan.ConcurrentModificationException;
import dev.nosurprise.loan.calc.LoanParamsOutOfRangeException;
import dev.nosurprise.loan.calc.LoanQuote;
import dev.nosurprise.loan.calc.LoanQuoteCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Типы займов: CRUD админа, публичный список и калькулятор (MICROLOANS.md §3.1, §3.2). */
@Service
public class LoanTypeService {

    private final LoanTypeRepository repository;
    private final Clock clock;

    public LoanTypeService(LoanTypeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LoanType> findActive() {
        return repository.findAllByActiveTrueOrderByName();
    }

    @Transactional(readOnly = true)
    public List<LoanType> findAll() {
        return repository.findAllByOrderByName();
    }

    @Transactional(readOnly = true)
    public LoanType get(UUID id) {
        return repository.findById(id).orElseThrow(LoanTypeNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Map<UUID, LoanType> getAll(Collection<UUID> ids) {
        return repository.findAllById(ids).stream().collect(Collectors.toMap(LoanType::getId, Function.identity()));
    }

    @Transactional
    public LoanType create(LoanTypeData data) {
        data.validate();
        return repository.save(new LoanType(data, clock.instant()));
    }

    /** version от клиента: два админа, правящих один тип, не перезапишут друг друга молча (§7.7). */
    @Transactional
    public LoanType update(UUID id, LoanTypeData data, long version) {
        data.validate();
        LoanType type = get(id);
        ConcurrentModificationException.check(type.getVersion(), version);
        type.apply(data, clock.instant());
        return repository.save(type);
    }

    @Transactional
    public LoanType deactivate(UUID id) {
        LoanType type = get(id);
        type.deactivate(clock.instant());
        return repository.save(type);
    }

    /** Расчёт по активному типу с проверкой лимитов. Тот же метод используется при подаче заявки. */
    @Transactional(readOnly = true)
    public LoanQuote quote(UUID typeId, BigDecimal amount, int termDays) {
        LoanType type = get(typeId);
        if (!type.isActive()) {
            throw new LoanTypeInactiveException();
        }
        return quote(type, amount, termDays);
    }

    public static LoanQuote quote(LoanType type, BigDecimal amount, int termDays) {
        if (amount.compareTo(type.getMinAmount()) < 0 || amount.compareTo(type.getMaxAmount()) > 0) {
            throw new LoanParamsOutOfRangeException("amount must be between " + type.getMinAmount().toPlainString()
                    + " and " + type.getMaxAmount().toPlainString() + " BYN");
        }
        if (termDays < type.getMinTermDays() || termDays > type.getMaxTermDays()) {
            throw new LoanParamsOutOfRangeException("term must be between " + type.getMinTermDays()
                    + " and " + type.getMaxTermDays() + " days");
        }
        return LoanQuoteCalculator.quote(amount, termDays, type.getDailyRate(), type.getModifier());
    }
}
