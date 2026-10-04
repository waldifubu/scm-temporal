package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;

import java.util.List;

public interface RequestComponentService {
    List<RequestComponent> findMyRequests(Long userId);
}
