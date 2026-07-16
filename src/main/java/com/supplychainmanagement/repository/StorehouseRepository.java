package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StorehouseRepository extends JpaRepository<Storehouse, String> {

}