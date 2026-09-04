package com.uniblox.store.report;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The administrative report.
 *
 * <p>This is a read model, so it is plain SQL rather than entities: the figures are
 * aggregates over historical snapshots, and nothing here is ever written back. Every
 * number is read from the columns that were frozen at checkout -- order line names,
 * quantities and line totals, and the order's own gross/discount/net -- never from
 * the live catalogue, so repricing or renaming a product cannot rewrite history.
 *
 * <p>"Successful orders" needs no filter: an order row exists only if its checkout
 * committed, because a failed one rolls back in its entirety.
 */
@Service
public class ReportService {

    private static final String REVENUE_TOTALS =
            """
            select count(*)                                    as order_count,
                   coalesce(sum(gross_total_cents), 0)::bigint as gross_revenue_cents,
                   coalesce(sum(discount_total_cents), 0)::bigint as total_discounts_cents,
                   coalesce(sum(net_total_cents), 0)::bigint   as net_revenue_cents
            from orders
            """;

    private static final String SALES_BY_PRODUCT =
            """
            select oi.product_id,
                   (array_agg(oi.product_name order by o.placed_at desc, oi.id desc))[1]
                                                            as product_name,
                   sum(oi.quantity)::bigint                 as quantity_purchased,
                   sum(oi.line_total_cents)::bigint         as revenue_cents
            from order_items oi
                     join orders o on o.id = oi.order_id
            group by oi.product_id
            order by oi.product_id
            """;

    private static final String COUPON_COUNTS =
            """
            select count(*)                                        as generated,
                   count(*) filter (where status = 'AVAILABLE')    as available,
                   count(*) filter (where status = 'REDEEMED')     as redeemed
            from coupons
            """;

    private final JdbcTemplate jdbc;

    ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReportResponse buildReport() {
        List<ReportResponse.ProductSales> productSales = jdbc.query(
                SALES_BY_PRODUCT,
                (row, index) -> new ReportResponse.ProductSales(
                        row.getLong("product_id"),
                        row.getString("product_name"),
                        row.getLong("quantity_purchased"),
                        row.getLong("revenue_cents")));

        ReportResponse.CouponSummary coupons = jdbc.queryForObject(
                COUPON_COUNTS,
                (row, index) -> new ReportResponse.CouponSummary(
                        row.getLong("generated"), row.getLong("available"), row.getLong("redeemed")));

        return jdbc.queryForObject(
                REVENUE_TOTALS,
                (row, index) -> new ReportResponse(
                        row.getLong("order_count"),
                        row.getLong("gross_revenue_cents"),
                        row.getLong("total_discounts_cents"),
                        row.getLong("net_revenue_cents"),
                        productSales,
                        coupons));
    }
}
