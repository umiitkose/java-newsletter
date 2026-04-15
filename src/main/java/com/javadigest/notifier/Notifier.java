package com.javadigest.notifier;

import com.javadigest.model.Article;
import java.util.List;

public interface Notifier {
    void send(List<Article> articles) throws Exception;
}
