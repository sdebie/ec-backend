package org.ecommerce.persistance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_gateway_logs")
public class PaymentLogEntity extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_id")
    public QuotationEntity quotationEntity;

    @Column(name = "gateway_name")
    public String gatewayName = "PAYFAST"; // Default for now

    @Column(name = "external_reference")
    public String externalReference; // pf_payment_id from PayFast

    @Column(name = "internal_reference")
    public String internalReference; // Your m_payment_id

    @Column(name = "amount_gross")
    public BigDecimal amountGross;

    @Column(name = "amount_fee")
    public BigDecimal amountFee;

    @Column(name = "amount_net")
    public BigDecimal amountNet;

    public String status; // COMPLETE, FAILED, PENDING

    @Column(columnDefinition = "TEXT")
    public String rawResponse; // The full POST body for auditing

    public LocalDateTime createdAt = LocalDateTime.now();
    public LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void setUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
    }
}