package com.be9expensphie.expense.controller;

import com.be9expensphie.expense.dto.AiPromptDTO;
import com.be9expensphie.expense.dto.CursorDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.TimeRange;
import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.service.ExpenseAiService;
import com.be9expensphie.expense.service.ExpenseReversalService;
import com.be9expensphie.expense.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("households/{householdId}/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseAiService expenseAiService;
    private final ExpenseService expenseService;
    private final ExpenseReversalService expenseReversalService;

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

    /*
     * 202, not 200. The reversal is in flight, not done. Telling the client
     * otherwise is exactly what would make the compensation path a lie -- the
     * admin must never see "reversed" for something settlement-service can
     * still refuse.
     */
    @PostMapping("/{expenseId}/reversal")
    public ResponseEntity<Map<String, Object>> requestReversal(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        ExpenseReversal reversal = expenseReversalService.requestReversal(householdId, expenseId, userId);
        return ResponseEntity.accepted().body(Map.of(
                "sagaId", reversal.getSagaId(),
                "state", reversal.getState().name()));
    }

    @GetMapping("/{expenseId}/reversal")
    public ResponseEntity<Map<String, Object>> reversalStatus(
            @PathVariable Long householdId,
            @PathVariable Long expenseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        ExpenseReversal reversal = expenseReversalService.status(householdId, expenseId, userId);
        /* HashMap, not Map.of: failureReason is null on the happy path. */
        Map<String, Object> body = new HashMap<>();
        body.put("sagaId", reversal.getSagaId());
        body.put("state", reversal.getState().name());
        body.put("failureReason", reversal.getFailureReason());
        return ResponseEntity.ok(body);
    }
}
