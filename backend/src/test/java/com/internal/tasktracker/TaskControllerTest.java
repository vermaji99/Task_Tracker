package com.internal.tasktracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchTasks_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void searchTasks_validStatus_returns200() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void searchTasks_archivedTaskDoesNotLeak() throws Exception {
        mockMvc.perform(get("/api/tasks").param("q", "deprecated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchTasks_statusFilterNotBypassedByTitleMatch() throws Exception {
        mockMvc.perform(get("/api/tasks").param("q", "login").param("status", "DONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
