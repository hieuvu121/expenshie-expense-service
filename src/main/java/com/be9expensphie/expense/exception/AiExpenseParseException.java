package com.be9expensphie.expense.exception;

import lombok.Getter;

@Getter
@SuppressWarnings("serial")
public class AiExpenseParseException extends RuntimeException {
    public AiExpenseParseException(String message) {
        super(message);
    }
}
