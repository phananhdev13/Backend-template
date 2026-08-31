package com.acme.order.ordering.config;

import com.acme.kernel.arch.ArchConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Commercial parameters that change without a deployment. */
@ArchConfig
@ConfigurationProperties(prefix = "acme.ordering")
public class OrderingProperties {

    /** Order subtotal above which the large-order discount applies. */
    private String discountThreshold = "500.00";

    /** Discount as a fraction of the subtotal. */
    private String discountRate = "0.05";

    /** Currency the threshold is expressed in. */
    private String currency = "EUR";

    public String getDiscountThreshold() {
        return discountThreshold;
    }

    public void setDiscountThreshold(String discountThreshold) {
        this.discountThreshold = discountThreshold;
    }

    public String getDiscountRate() {
        return discountRate;
    }

    public void setDiscountRate(String discountRate) {
        this.discountRate = discountRate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
