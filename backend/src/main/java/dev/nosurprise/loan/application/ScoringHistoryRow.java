package dev.nosurprise.loan.application;

/** Строка сводки истории для скоринга (native-проекция {@link LoanApplicationRepository#scoringHistory}). */
public interface ScoringHistoryRow {

    long getSignedLoans();

    long getRepaidOnTime();

    long getRepaidLate();

    int getMaxOverdueDays();

    boolean getOverdueNow();

    long getRejectedLast12Months();
}
