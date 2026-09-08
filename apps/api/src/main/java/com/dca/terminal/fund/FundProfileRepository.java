package com.dca.terminal.fund;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundProfileRepository extends JpaRepository<FundProfileEntity, UUID> {
}
