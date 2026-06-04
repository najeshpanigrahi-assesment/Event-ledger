package com.eventledger.account.service;

import com.eventledger.account.dto.*;
import com.eventledger.account.entity.Account;
import com.eventledger.account.entity.Transaction;
import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.filter.TraceContext;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final Counter transactionsAppliedCounter;
    private final Counter duplicateTransactionsCounter;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionsAppliedCounter = Counter.builder("transactions.applied.total")
                .description("Total transactions applied to accounts")
                .register(meterRegistry);
        this.duplicateTransactionsCounter = Counter.builder("transactions.duplicate.total")
                .description("Total duplicate transactions received")
                .register(meterRegistry);
    }

    /**
     * Apply a transaction to an account. Idempotent — duplicate eventId is a no-op.
     * Handles out-of-order: balance is always recomputed from all transactions.
     */
    @Transactional
    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {
        String traceId = TraceContext.getTraceId();
        log.info("Applying transaction | accountId={} | eventId={} | type={} | amount={} | traceId={}",
                accountId, request.getEventId(), request.getType(), request.getAmount(), traceId);

        // Idempotency check
        if (transactionRepository.existsByEventId(request.getEventId())) {
            duplicateTransactionsCounter.increment();
            log.info("Duplicate transaction ignored | eventId={} | traceId={}", request.getEventId(), traceId);
            return transactionRepository.findByEventId(request.getEventId())
                    .map(this::toResponse)
                    .orElseThrow();
        }

        // Ensure account exists (create if first transaction)
        Account account = accountRepository.findById(accountId).orElseGet(() -> {
            log.info("Auto-creating account | accountId={} | traceId={}", accountId, traceId);
            return accountRepository.save(Account.builder()
                    .accountId(accountId)
                    .balance(BigDecimal.ZERO)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
        });

        // Parse eventTimestamp
        Instant eventTimestamp = Instant.parse(request.getEventTimestamp());

        // Persist transaction
        Transaction tx = Transaction.builder()
                .eventId(request.getEventId())
                .accountId(accountId)
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(eventTimestamp)
                .appliedAt(Instant.now())
                .build();
        transactionRepository.save(tx);

        // Recompute balance from all transactions (handles out-of-order correctly)
        BigDecimal newBalance = transactionRepository.calculateBalance(accountId);
        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        transactionsAppliedCounter.increment();
        log.info("Transaction applied | accountId={} | eventId={} | newBalance={} | traceId={}",
                accountId, request.getEventId(), newBalance, traceId);

        return toResponse(tx);
    }

    /**
     * Get account balance.
     */
    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        log.info("Getting balance | accountId={} | traceId={}", accountId, TraceContext.getTraceId());
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        return BalanceResponse.builder()
                .accountId(accountId)
                .balance(account.getBalance())
                .currency("USD")
                .asOf(Instant.now())
                .build();
    }

    /**
     * Get account details with recent transactions.
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        log.info("Getting account details | accountId={} | traceId={}", accountId, TraceContext.getTraceId());
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        List<TransactionResponse> transactions = transactionRepository
                .findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return AccountResponse.builder()
                .accountId(account.getAccountId())
                .balance(account.getBalance())
                .currency("USD")
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .recentTransactions(transactions)
                .build();
    }

    private TransactionResponse toResponse(Transaction tx) {
        return TransactionResponse.builder()
                .eventId(tx.getEventId())
                .accountId(tx.getAccountId())
                .type(tx.getType())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .eventTimestamp(tx.getEventTimestamp())
                .appliedAt(tx.getAppliedAt())
                .build();
    }
}
