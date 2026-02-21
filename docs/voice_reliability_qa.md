# Voice Reliability QA Checklist

This document outlines the expected behavior and verification steps for the OpenClaw Assistant voice interaction subsystem.

## Assistant State Transitions

The voice session follows these states defined in `AssistantState`:

| State | Description | Expected Next State |
|-------|-------------|---------------------|
| `IDLE` | Waiting for activation. | `LISTENING` |
| `LISTENING` | Actively recording user audio. | `PROCESSING` (on speech end) or `ERROR` |
| `PROCESSING` | Audio is being processed by the recognizer. | `THINKING` (if text received) or `ERROR` |
| `THINKING` | AI is generating a response. | `SPEAKING` (if TTS enabled) or `IDLE` |
| `SPEAKING` | AI response is being played back via TTS. | `LISTENING` (continuous) or `IDLE` |
| `ERROR` | A failure occurred. Displays error message. | `IDLE` (on close) or `LISTENING` (on retry) |

## Reliability Check Tool

Users and maintainers can use the built-in "Voice Reliability Check" in Settings -> Support.

### Steps performed by the tool:
1. **TTS Init**: Verifies the Text-to-Speech engine can be initialized.
2. **TTS Speak**: Plays a test phrase. If you don't hear anything, check system volume or TTS engine settings.
3. **STT Start**: Verifies the Speech Recognizer can start and reach the "Ready" state.
4. **Cleanup**: Ensures all resources are properly released.

## Manual Test Scenarios

Verify these scenarios after making changes to voice logic:

### 1. Interruption (Talk-over)
- **Action**: Speak or tap the mic while the AI is speaking.
- **Expected**: TTS should stop immediately, and the assistant should return to `LISTENING` mode.

### 2. Continuous Mode
- **Action**: Complete a voice interaction with "Continuous Conversation" enabled.
- **Expected**: After TTS finishes, the assistant should automatically start `LISTENING` again without user intervention.

### 3. Hard Recognizer Errors
- **Action**: Trigger situations where the recognizer is busy or unavailable.
- **Expected**: The system should retry up to 3 times before showing an error. It MUST NOT enter an infinite restart loop.

### 4. Bluetooth Headset
- **Action**: Connect a BT headset and start a session.
- **Expected**: Audio input and output should correctly route through the headset.

### 5. Screen Off
- **Action**: Start a conversation and turn off the screen.
- **Expected**: The conversation should continue (the foreground service ensures the process isn't killed).

## Diagnostic Report

The report includes:
- Device Model & Android Version
- Active STT Provider (e.g., Google, System Default)
- TTS Engine Status
- Recent Error Codes

If reporting a voice issue, please attach the copy-pasted diagnostic report.
