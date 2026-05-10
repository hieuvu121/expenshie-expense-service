package com.be9expensphie.expense.service;

import com.be9expensphie.common.event.AiRequestEvent;
import com.be9expensphie.common.event.AiRequestType;
import com.be9expensphie.common.event.AiResponseEvent;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.exception.AiExpenseParseException;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseAiService {

    private final ReplyingKafkaTemplate<String, AiRequestEvent, AiResponseEvent> replyingKafkaTemplate;
    private final ExpenseService expenseService;
    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;
    private final ObjectMapper objectMapper;

    public CreateExpenseResponseDTO createExpenseFromPrompt(Long householdId, String prompt, Long userId) {
        HouseholdMemberSummary member = householdMemberSummaryRepo
                .findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User is not in this household"));

        ProducerRecord<String, AiRequestEvent> producerRecord = new ProducerRecord<>(
                "ai-request-events",
                AiRequestEvent.builder()
                        .type(AiRequestType.PARSE_EXPENSE)
                        .householdId(householdId)
                        .prompt(prompt)
                        .build()
        );

        RequestReplyFuture<String, AiRequestEvent, AiResponseEvent> future =
                replyingKafkaTemplate.sendAndReceive(producerRecord);

        AiResponseEvent response;
        try {
            ConsumerRecord<String, AiResponseEvent> reply = future.get(10, TimeUnit.SECONDS);
            response = reply.value();
        } catch (Exception e) {
            log.error("AI request timed out or failed for householdId={}", householdId, e);
            throw new AiExpenseParseException("AI service did not respond in time");
        }

        if (!response.isSuccess()) {
            throw new AiExpenseParseException("AI failed to parse expense: " + response.getErrorMessage());
        }

        CreateExpenseRequestDTO dto = parseAiJson(response.getParsedJson(), member.getMemberId());
        return expenseService.createExpense(householdId, dto, userId);
    }

    @SuppressWarnings("unchecked")
    private CreateExpenseRequestDTO parseAiJson(String json, Long memberId) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            BigDecimal amount = new BigDecimal(map.get("amount").toString());

            // requester is the sole split — admin redistributes when approving
            return CreateExpenseRequestDTO.builder()
                    .amount(amount)
                    .category((String) map.get("category"))
                    .description((String) map.getOrDefault("description", ""))
                    .currency((String) map.getOrDefault("currency", "AUD"))
                    .method(Method.valueOf(((String) map.getOrDefault("method", "EQUAL")).toUpperCase()))
                    .date(LocalDate.parse((String) map.getOrDefault("date", LocalDate.now().toString())))
                    .splits(List.of(SplitRequestDTO.builder().memberId(memberId).amount(amount).build()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse AI JSON: {}", json, e);
            throw new AiExpenseParseException("AI returned invalid JSON: " + e.getMessage());
        }
    }

    @Cacheable(key = "#householdId", cacheNames = "ai_suggestion")
    public String getExpenseSuggestions(Long householdId, Long userId) {
        householdMemberSummaryRepo.findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User is not in this household"));

        List<CreateExpenseResponseDTO> expenses = expenseService.getExpenseLastMonth(householdId);
        if(expenses.isEmpty()){
            return "No expense created in this household";
        }

        StringBuilder prompt=new StringBuilder();
        prompt.append("Based on the following household expenses from the last month, ")
                .append("provide practical financial suggestions and spending insights:\n\n");
        for (CreateExpenseResponseDTO expense : expenses) {
            prompt.append(String.format("- Category: %s | Currency: %s | Amount: %s | Date: %s%n",
                    expense.getCategory(),
                    expense.getCurrency(),
                    expense.getAmount(),
                    expense.getDate()));
        }

        prompt.append("\nPlease provide (friendly language, keep it concise):\n")
                .append("1. Spending pattern analysis\n")
                .append("2. Cost-saving suggestions\n")
                .append("3. Budget recommendations\n");

        ProducerRecord<String,AiRequestEvent> producerRecord=new ProducerRecord<>(
                "ai-request-events",
                AiRequestEvent.builder()
                        .type(AiRequestType.GENERATE_SUGGESTION)
                        .householdId(householdId)
                        .prompt(prompt.toString())
                        .build()
        );
        RequestReplyFuture<String, AiRequestEvent, AiResponseEvent> future =
                replyingKafkaTemplate.sendAndReceive(producerRecord);

        AiResponseEvent response;
        try {
            ConsumerRecord<String, AiResponseEvent> reply = future.get(15, TimeUnit.SECONDS);
            response = reply.value();
        } catch (Exception e) {
            log.error("Suggestion request timed out for householdId={}", householdId, e);
            throw new AiExpenseParseException("AI service did not respond in time");
        }

        if (!response.isSuccess()) {
            throw new AiExpenseParseException("AI failed to generate suggestion: " + response.getErrorMessage());
        }

        return response.getParsedJson();
    }
}
