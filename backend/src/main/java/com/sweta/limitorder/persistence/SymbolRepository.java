package com.sweta.limitorder.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SymbolRepository extends JpaRepository<Symbol, String> {
}
