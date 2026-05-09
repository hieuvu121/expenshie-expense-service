package com.be9expensphie.expense.controller;

import com.be9expensphie.expense.dto.AiPromptDTO;
import com.be9expensphie.expense.dto.CursorDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.TimeRange;
import com.be9expensphie.expense.service.ExpenseAiService;
import com.be9expensphie.expense.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("households/{householdId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseAiService expenseAiService;
    private final ExpenseService expenseService;

    @PostMapping
    public ResponseEntity<CreateExpenseResponseDTO> createExpense(
            @PathVariable Long householdId,
            @Valid @RequestBody CreateExpenseRequestDTO request,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseService.createExpense(householdId, request, userId));
    }

    @GetMapping
    public ResponseEntity<CursorDTO<CreateExpenseResponseDTO>> getExpenses(
            @PathVariable Long householdId,
            @RequestParam(required = false) ExpenseStatus status,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Long cursor,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseService.getExpense(householdId, status, limit, cursor, userId));
    }

    @GetMapping("/{expenseId}")
    public ResponseEntity<CreateExpenseResponseDTO> getSingleExpense(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseService.getSingleExpense(householdId, expenseId, userId));
    }

    @PatchMapping("/{expenseId}/approve")
    public ResponseEntity<?> approveExpense(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseService.acceptExpense(householdId, expenseId, userId));
    }

    @PatchMapping("/{expenseId}/update")
    public ResponseEntity<?> updateExpense(
            @PathVariable Long expenseId,
            @PathVariable Long householdId,
            @Valid @RequestBody CreateExpenseRequestDTO request,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(expenseService.updateExpense(householdId, expenseId, request, userId));
    }

    @DeleteMapping("/{expenseId}/reject")
    public ResponseEntity<?> rejectExpense(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        expenseService.rejectExpense(householdId, expenseId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{range}/{status}")
    public ResponseEntity<List<CreateExpenseResponseDTO>> getRangeExpense(
            @PathVariable Long householdId,
            @PathVariable TimeRange range,
            @PathVariable ExpenseStatus status
    ) {
        return ResponseEntity.ok(expenseService.getExpenseByPeriod(status, householdId, range));
    }

    @GetMapping("/last-month")
    public ResponseEntity<List<CreateExpenseResponseDTO>> getLastMonthExpense(
            @PathVariable Long householdId
    ) {
        return ResponseEntity.ok(expenseService.getExpenseLastMonth(householdId));
    }

    @PostMapping("/ai")
    public ResponseEntity<CreateExpenseResponseDTO> createExpenseAI(
            @PathVariable Long householdId,
            @RequestBody AiPromptDTO body,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ResponseEntity.ok(
                expenseAiService.createExpenseFromPrompt(householdId, body.getPrompt(), userId)
        );
    }
}
