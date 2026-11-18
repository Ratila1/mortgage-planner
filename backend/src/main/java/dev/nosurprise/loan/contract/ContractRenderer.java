package dev.nosurprise.loan.contract;

/** Порт генерации PDF договора (MICROLOANS.md §7.4). */
public interface ContractRenderer {

    byte[] render(ContractData data);
}
