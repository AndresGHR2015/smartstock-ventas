package com.smartstock.sales;

import com.smartstock.contracts.sales.v1.RejectionReason;
import com.smartstock.contracts.sales.v1.Sale;
import com.smartstock.contracts.sales.v1.SaleStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SaleStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SaleStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Sale createOrGet(String id, String sku, int quantity) {
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO sales (id_hash, sale_id, sku, quantity, status) VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT (id_hash) DO NOTHING",
                    hash(id), bytes(id), bytes(sku), quantity);
            return find(id).orElseThrow();
        });
    }

    public Optional<Sale> find(String id) {
        List<Sale> rows = jdbc.query("SELECT sale_id, sku, quantity, status, rejection_reason FROM sales WHERE id_hash = ?",
                (rs, row) -> {
                    String storedId = new String(rs.getBytes("sale_id"), StandardCharsets.UTF_8);
                    if (!storedId.equals(id)) {
                        throw new IllegalStateException("Sale ID hash collision");
                    }
                    Sale.Builder sale = Sale.newBuilder().setSaleId(storedId)
                            .setSku(new String(rs.getBytes("sku"), StandardCharsets.UTF_8))
                            .setQuantity(rs.getInt("quantity")).setStatus(SaleStatus.valueOf(rs.getString("status")));
                    String reason = rs.getString("rejection_reason");
                    if (reason != null) {
                        sale.setRejectionReason(RejectionReason.valueOf(reason));
                    }
                    return sale.build();
                }, hash(id));
        return rows.stream().findFirst();
    }

    public List<String> pending(int limit) {
        return jdbc.query("SELECT sale_id FROM sales WHERE status = 'PENDING' ORDER BY last_attempt NULLS FIRST, created_at LIMIT ?",
                (rs, row) -> new String(rs.getBytes("sale_id"), StandardCharsets.UTF_8), limit);
    }

    public void markAttempt(String id) {
        jdbc.update("UPDATE sales SET last_attempt = now() WHERE id_hash = ? AND sale_id = ? AND status = 'PENDING'", hash(id), bytes(id));
    }

    public void complete(String id, SaleStatus status, RejectionReason reason) {
        jdbc.update("UPDATE sales SET status = ?, rejection_reason = ? WHERE id_hash = ? AND sale_id = ? AND status = 'PENDING'",
                status.name(), reason == RejectionReason.REJECTION_REASON_UNSPECIFIED ? null : reason.name(), hash(id), bytes(id));
    }

    private static byte[] hash(String id) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes(id));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
