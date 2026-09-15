package com.gpmanager.reward;

/**
 * Presentation phase for the current reward. Reveal expands under the HUD
 * header; settled keeps the best item row until the next eligible reward.
 */
public enum RewardPresentationPhase
{
    NONE,
    REVEALING,
    SETTLED
}
