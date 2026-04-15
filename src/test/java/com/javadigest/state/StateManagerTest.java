package com.javadigest.state;

import com.javadigest.model.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateManagerTest {

    private static final String TEST_STATE = "test-state-" + System.nanoTime() + ".json";

    @BeforeEach
    void setUp() {
        new File("state.json").delete();
    }

    @Test
    void filterNew_shouldReturnOnlyUnseenArticles() {
        StateManager state = new StateManager();

        Article a1 = article("url-a1");
        Article a2 = article("url-a2");
        Article a3 = article("url-a3");

        state.markAsSeen(List.of(a1));

        List<Article> fresh = state.filterNew(List.of(a1, a2, a3));

        assertEquals(2, fresh.size());
        assertTrue(fresh.stream().anyMatch(a -> a.id().equals("url-a2")));
        assertTrue(fresh.stream().anyMatch(a -> a.id().equals("url-a3")));
        assertFalse(fresh.stream().anyMatch(a -> a.id().equals("url-a1")));
    }

    @Test
    void filterNew_emptyState_shouldReturnAll() {
        StateManager state = new StateManager();

        Article a1 = article("url-b1");
        Article a2 = article("url-b2");

        List<Article> fresh = state.filterNew(List.of(a1, a2));

        assertEquals(2, fresh.size());
    }

    @Test
    void markAsSeen_shouldPersistBetweenInstances() {
        Article a1 = article("url-c1");

        StateManager state1 = new StateManager();
        state1.markAsSeen(List.of(a1));

        StateManager state2 = new StateManager();
        List<Article> fresh = state2.filterNew(List.of(a1));

        assertEquals(0, fresh.size());
    }

    private static Article article(String id) {
        return new Article(id, "Title", id, "Author", "test", LocalDate.now(), "");
    }
}
