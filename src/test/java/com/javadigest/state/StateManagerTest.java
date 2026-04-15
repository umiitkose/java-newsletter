package com.javadigest.state;

import com.javadigest.model.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateManagerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("user.dir", tempDir.toString());
    }

    @Test
    void filterNew_shouldReturnOnlyUnseenArticles() {
        StateManager state = new StateManager();

        Article a1 = new Article("url1", "Title 1", "url1", "Author", "source", LocalDate.now(), "");
        Article a2 = new Article("url2", "Title 2", "url2", "Author", "source", LocalDate.now(), "");
        Article a3 = new Article("url3", "Title 3", "url3", "Author", "source", LocalDate.now(), "");

        state.markAsSeen(List.of(a1));

        List<Article> fresh = state.filterNew(List.of(a1, a2, a3));

        assertEquals(2, fresh.size());
        assertTrue(fresh.stream().anyMatch(a -> a.id().equals("url2")));
        assertTrue(fresh.stream().anyMatch(a -> a.id().equals("url3")));
        assertFalse(fresh.stream().anyMatch(a -> a.id().equals("url1")));
    }

    @Test
    void filterNew_emptyState_shouldReturnAll() {
        StateManager state = new StateManager();

        Article a1 = new Article("url1", "Title 1", "url1", "Author", "source", LocalDate.now(), "");
        Article a2 = new Article("url2", "Title 2", "url2", "Author", "source", LocalDate.now(), "");

        List<Article> fresh = state.filterNew(List.of(a1, a2));

        assertEquals(2, fresh.size());
    }

    @Test
    void markAsSeen_shouldPersistBetweenInstances() {
        StateManager state1 = new StateManager();
        Article a1 = new Article("url1", "Title 1", "url1", "Author", "source", LocalDate.now(), "");
        state1.markAsSeen(List.of(a1));

        StateManager state2 = new StateManager();
        List<Article> fresh = state2.filterNew(List.of(a1));

        assertEquals(0, fresh.size());
    }
}
