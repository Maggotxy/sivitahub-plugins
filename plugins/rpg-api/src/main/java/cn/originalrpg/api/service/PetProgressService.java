package cn.originalrpg.api.service;

import cn.originalrpg.api.model.PetCollectionEntry;
import cn.originalrpg.api.model.PetProgress;
import cn.originalrpg.api.model.PetProgressResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Transactional companion progression; all methods execute off the server thread. */
public interface PetProgressService {
    CompletionStage<PetProgress> registerCapture(
            UUID ownerId, UUID petId, String speciesId, String personalityId, String traitId);

    CompletionStage<Optional<PetProgress>> progress(UUID ownerId, UUID petId);

    CompletionStage<List<PetCollectionEntry>> collection(UUID ownerId);

    CompletionStage<PetProgressResult> rewardBond(
            UUID ownerId, UUID petId, int amount, String source, String idempotencyKey);

    CompletionStage<PetProgressResult> evolve(
            UUID ownerId, UUID petId, int expectedStage, int minimumBond, String unlockedSkinId);

    CompletionStage<PetProgressResult> selectSkin(UUID ownerId, UUID petId, String skinId);
}
