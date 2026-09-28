package com.be9expensphie.expense.enums;

public enum ExpenseStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /* Reversal in flight. Legitimate and user-visible, not a lock. */
    REVERSING,
    /* Terminal and immutable. A correction is a new expense, never an edit. */
    REVERSED
}
