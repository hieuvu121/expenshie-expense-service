package com.be9expensphie.expense.controller;

import com.be9expensphie.expense.service.ExpenseAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("households/{householdId}/suggestions")
@RequiredArgsConstructor
public class SuggestionController {

    private final ExpenseAiService expenseAiService;

    @GetMapping
    public ResponseEntity<String> getSuggestions(
            @PathVariable Long householdId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseAiService.getExpenseSuggestions(householdId, userId));
    }
}
