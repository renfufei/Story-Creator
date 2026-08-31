package com.storycreator.txtimport;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import com.storycreator.persistence.repository.ChapterSplitConfigRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class ChapterSplitConfigService {

    private final ChapterSplitConfigRepository repository;

    public ChapterSplitConfigService(ChapterSplitConfigRepository repository) {
        this.repository = repository;
    }

    public List<ChapterSplitConfigEntity> listAll() {
        return repository.findAllByOrderBySortOrder();
    }

    public List<ChapterSplitConfigEntity> listEnabled() {
        return repository.findByEnabledTrueOrderBySortOrder();
    }

    public ChapterSplitConfigEntity getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
    }

    public ChapterSplitConfigEntity create(String name, String description, String pattern,
                                           int titleGroup, boolean includeMatch) {
        validatePattern(pattern);
        ChapterSplitConfigEntity entity = new ChapterSplitConfigEntity();
        entity.setName(name);
        entity.setDescription(description);
        entity.setPattern(pattern);
        entity.setTitleGroup(titleGroup);
        entity.setIncludeMatch(includeMatch);
        entity.setBuiltin(false);
        entity.setEnabled(true);
        entity.setSortOrder(100);
        return repository.save(entity);
    }

    public ChapterSplitConfigEntity update(Long id, String name, String description, String pattern,
                                           int titleGroup, boolean includeMatch) {
        ChapterSplitConfigEntity entity = getById(id);
        if (entity.isBuiltin()) {
            throw new IllegalStateException("内置配置不可修改");
        }
        validatePattern(pattern);
        entity.setName(name);
        entity.setDescription(description);
        entity.setPattern(pattern);
        entity.setTitleGroup(titleGroup);
        entity.setIncludeMatch(includeMatch);
        return repository.save(entity);
    }

    public void toggle(Long id) {
        ChapterSplitConfigEntity entity = getById(id);
        entity.setEnabled(!entity.isEnabled());
        repository.save(entity);
    }

    public void delete(Long id) {
        ChapterSplitConfigEntity entity = getById(id);
        if (entity.isBuiltin()) {
            throw new IllegalStateException("内置配置不可删除");
        }
        repository.delete(entity);
    }

    public TestResult testPattern(String pattern, String sampleText) {
        validatePattern(pattern);
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(sampleText);
        List<String> matches = new ArrayList<>();
        while (m.find()) {
            matches.add(m.group());
        }
        return new TestResult(matches.size(), matches);
    }

    private void validatePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("正则表达式无效: " + e.getDescription());
        }
    }

    public record TestResult(int matchCount, List<String> matches) {}
}
