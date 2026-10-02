package cn.originalrpg.api.service;

import cn.originalrpg.api.model.ChapterDefinition;
import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.ChapterTransitionResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Global, monotonic content chapter. It gates progression but owns no player rewards. */
public interface ChapterService {
    ChapterState current();

    List<ChapterDefinition> definitions();

    Optional<ChapterDefinition> next();

    CompletionStage<ChapterState> refresh();

    CompletionStage<ChapterTransitionResult> advance(String targetChapterId, String actor, String reason);
}
