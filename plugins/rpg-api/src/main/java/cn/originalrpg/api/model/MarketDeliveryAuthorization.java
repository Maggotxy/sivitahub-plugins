package cn.originalrpg.api.model;

/**
 * Result of the one-way CLAIMED -> STAGING transition. Only AUTHORIZED permits
 * this one callback to stage a physical item. A repeat or uncertain response is
 * INDETERMINATE; resolve it using an existing item receipt, never another insert.
 */
public enum MarketDeliveryAuthorization { AUTHORIZED, INDETERMINATE, EXPIRED, REFUSED }
