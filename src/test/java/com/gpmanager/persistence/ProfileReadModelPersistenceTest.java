package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.model.GoalDefinition;
import com.gpmanager.model.TileLayout;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileReadModelPersistenceTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void goalsAndTileLayoutRoundTripInTheirOwningProfiles() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Gson gson = new Gson();
        SessionRepository repository = new SessionRepository(gson, root, null, true);
        TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
        TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");

        TileLayout.PageLayout aliceLive = new TileLayout.PageLayout(
            Arrays.asList("party", "recent"), new LinkedHashSet<>(Collections.singletonList("notices")));
        Map<String, TileLayout.PageLayout> alicePages = new LinkedHashMap<>();
        alicePages.put("live", aliceLive);
        SavedState aliceState = new SavedState();
        aliceState.setGoalDefinitions(Collections.singletonList(new GoalDefinition(
            GoalDefinition.Kind.KILLS, GoalDefinition.Scope.SESSION, 25L, Arrays.asList(50, 100))));
        String aliceGoalId = aliceState.getGoalDefinitions().get(0).getId();
        aliceState.getGoalDefinitions().get(0).setTargetValue(900L);
        assertEquals(25L, aliceState.getGoalDefinitions().get(0).getTargetValue());
        aliceState.setTileLayout(new TileLayout(alicePages));
        repository.bindIdentity(alice);
        repository.save(aliceState);

        SavedState bobState = new SavedState();
        bobState.setGoalDefinitions(Collections.singletonList(new GoalDefinition(
            GoalDefinition.Kind.NET, GoalDefinition.Scope.TODAY, 500_000L, Collections.singletonList(100))));
        bobState.setTileLayout(TileLayout.legacyDefaults());
        repository.bindIdentity(bob);
        repository.save(bobState);

        repository.bindIdentity(alice);
        SavedState aliceRestored = repository.load();
        assertEquals(GoalDefinition.Kind.KILLS, aliceRestored.getGoalDefinitions().get(0).getKind());
        assertEquals(aliceGoalId, aliceRestored.getGoalDefinitions().get(0).getId());
        assertEquals("party", aliceRestored.getTileLayout().getOrderedTileIds("live").get(0));
        assertTrue(aliceRestored.getTileLayout().isHidden("live", "notices"));

        repository.bindIdentity(bob);
        SavedState bobRestored = repository.load();
        assertEquals(GoalDefinition.Kind.NET, bobRestored.getGoalDefinitions().get(0).getKind());
        assertEquals(500_000L, bobRestored.getGoalDefinitions().get(0).getTargetValue());
        assertTrue(bobRestored.getTileLayout().getPages().isEmpty());
        assertFalse(alice.equals(bob));
    }

    @Test
    public void legacySavedStateDefaultsToNoGoalAndNoLayoutOverrides()
    {
        SavedState restored = new Gson().fromJson("{}", SavedState.class);

        assertTrue(restored.getGoalDefinitions().isEmpty());
        assertTrue(restored.getTileLayout().getPages().isEmpty());
    }
}
