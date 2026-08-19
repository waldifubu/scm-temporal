package com.supplychainmanagement.event;

import java.time.LocalDateTime;

public record OrderCreatedEvent(String orderNo, String customerName, LocalDateTime orderDate) {

}
