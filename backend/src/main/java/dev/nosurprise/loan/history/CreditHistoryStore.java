package dev.nosurprise.loan.history;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Порт хранилища кредитной истории (§7.4). Запись — только из consumer-а и пересборки (§4.9). */
public interface CreditHistoryStore {

    /** Заменяет документ, только если он новее сохранённого (по lastEventId). true — документ записан. */
    boolean replaceIfNewer(CreditHistoryDocument document);

    List<CreditHistoryDocument> findByUser(UUID userId);

    long count();

    Set<UUID> existingIds(Collection<UUID> ids);
}
