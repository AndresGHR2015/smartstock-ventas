package com.smartstock.sales;

import com.smartstock.contracts.sales.v1.RejectionReason;
import com.smartstock.contracts.sales.v1.GetSalesOverviewResponse;
import com.smartstock.contracts.sales.v1.RecentSale;
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

    public GetSalesOverviewResponse overview() {
        return jdbc.query("""
                WITH local_day AS (
                    SELECT (now() AT TIME ZONE 'America/Santiago')::date AS today
                ), totals AS (
                    SELECT coalesce(sum(quantity), 0) AS units_today
                    FROM sales CROSS JOIN local_day
                    WHERE status = 'CONFIRMED'
                      AND created_at >= (today::timestamp AT TIME ZONE 'America/Santiago')
                      AND created_at < ((today + 1)::timestamp AT TIME ZONE 'America/Santiago')
                )
                SELECT units_today, recent.sale_id, recent.sku, recent.quantity, recent.created_at
                FROM totals LEFT JOIN LATERAL (
                    SELECT sale_id, sku, quantity, created_at FROM sales
                    WHERE status = 'CONFIRMED' ORDER BY created_at DESC, sale_id LIMIT 5
                ) recent ON true
                ORDER BY recent.created_at DESC, recent.sale_id
                """, rs -> {
            GetSalesOverviewResponse.Builder overview = GetSalesOverviewResponse.newBuilder();
            while (rs.next()) {
                overview.setUnitsToday(rs.getLong("units_today"));
                byte[] id = rs.getBytes("sale_id");
                if (id != null) {
                    overview.addRecent(RecentSale.newBuilder()
                            .setSaleId(new String(id, StandardCharsets.UTF_8))
                            .setSku(new String(rs.getBytes("sku"), StandardCharsets.UTF_8))
                            .setQuantity(rs.getInt("quantity"))
                            .setCreatedAt(rs.getTimestamp("created_at").toInstant().toString()));
                }
            }
            return overview.build();
        });
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
