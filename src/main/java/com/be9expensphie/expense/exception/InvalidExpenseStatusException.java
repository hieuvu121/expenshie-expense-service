package com.be9expensphie.expense.exception;

public class InvalidExpenseStatusException extends RuntimeException{
    public InvalidExpenseStatusException(String message){
        super(message);
    }
}
