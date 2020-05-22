package com.company.subdomain.resilience.refapp.model;

import com.company.subdomain.resilience.refapp.enums.CouponType;
import com.company.subdomain.resilience.refapp.enums.MarketType;
import com.company.subdomain.resilience.refapp.enums.ProductType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class Offering {

    private ProductType productType;
    private String cusip;
    private String description;
    private String state;
    private String ticker;
    private CouponType couponType;

    private String industry;
    private BigDecimal coupon;
    private LocalDate maturityDate;
    private boolean callable;
    private boolean taxable;
    private String moodyRating;
    private String snpRating;

    private String offerId;

    //added for calculation
    private BigDecimal duration;
    private BigDecimal convexity;
    private MarketType marketType;


    private int askQty;
    private int askMinQty;
    private int askMinIncrement;
    private BigDecimal askPrice;
    private BigDecimal askYtw;
    private BigDecimal askYtm;

    private int bidQty;
    private int bidMinQty;
    private int bidMinIncrement;
    private BigDecimal bidPrice;
    private BigDecimal bidYtw;
    private BigDecimal bidYtm;

    //attributes to capture mark up values
    private BigDecimal askDeltaPrice;
    private BigDecimal askDeltaYield;
    private BigDecimal bidDeltaPrice;
    private BigDecimal bidDeltaYield;
}
