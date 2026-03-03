package org.ecommerce.common.enums;

public enum StaffRoleEn {
    SUPER_ADMIN,      // Full access to everything
    CATALOG_MANAGER,  // Manage products, categories, and brands
    ORDER_MANAGER,    // Manage customer orders and shipping
    VIEWER           // Read-only access to the dashboard
}