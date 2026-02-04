package com.limitcache.repository;

import com.limitcache.model.TestHistory;
import com.limitcache.model.TestHistory.TestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestHistoryRepository extends JpaRepository<TestHistory, Long> {

    List<TestHistory> findTop20ByTestTypeOrderByCreatedAtDesc(TestType testType);
}
