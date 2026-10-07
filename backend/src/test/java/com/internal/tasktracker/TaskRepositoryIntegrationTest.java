package com.internal.tasktracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TaskRepositoryIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Test
    void searchTasks_shouldExcludeArchivedTasksEvenWhenDescriptionMatches() {
        List<Task> results = taskRepository.searchTasks("%deprecated%", null);
        assertTrue(results.stream().noneMatch(t -> "Legacy API cleanup".equals(t.getTitle())),
                "Archived task 'Legacy API cleanup' (description contains 'deprecated') must be excluded");
    }

    @Test
    void searchTasks_statusFilterMustApplyToTitleMatches() {
        List<Task> results = taskRepository.searchTasks("%login%", "DONE");
        assertTrue(results.stream().noneMatch(t -> "Fix login redirect bug".equals(t.getTitle())),
                "Task with status OPEN must not appear when filter is DONE even if title matches");
    }

    @Test
    void searchTasks_noFilter_returnsOnlyNonArchived() {
        List<Task> results = taskRepository.searchTasks("%%", null);
        assertTrue(results.stream().allMatch(t -> !t.isArchived()),
                "All returned tasks must be non-archived when no filters applied");
    }
}
