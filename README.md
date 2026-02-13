# OpenClaw Assistant

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)

**[日本語版はこちら](#日本語) | English below**

📹 **Demo Video**: https://x.com/i/status/2017914589938438532

---

## English

**Your AI Assistant in Your Pocket** - A dedicated Android voice assistant app for OpenClaw.

### ✨ Features

- 🎤 **Customizable Wake Word** - Choose from "Open Claw", "Jarvis", "Computer", or set your own
- 🏠 **Long Press Home Button** - Works as a system assistant
- 🔄 **Continuous Conversation Mode** - Natural dialogue with session persistence
- 🔊 **Voice Output** - Automatic text-to-speech for AI responses
- 💬 **In-App Chat** - Hybrid text & voice input
- 🔒 **Privacy First** - Settings stored with encryption
- 📴 **Offline Wake Word Detection** - Local processing with Vosk

### 📱 How to Use

1. **Long press Home button** or say the **wake word**
2. Ask your question or make a request
3. OpenClaw responds with voice
4. Continue the conversation (session maintained)

### 🚀 Setup

#### 1. Install the App

Download APK from [Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases), or build from source.

#### 2. Configuration

1. Open the app
2. Tap ⚙️ in the top right to open Settings
3. Enter:
   - **Webhook URL** (required): Your OpenClaw endpoint
   - **Auth Token** (optional): Bearer authentication

#### 3. Wake Word Setup

1. Open "Wake Word" section in Settings
2. Choose a preset:
   - **Open Claw** (default)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (enter your own)
3. Enable the Wake Word toggle on the home screen

#### 4. Set as System Assistant

1. Tap "Home Button" card in the app
2. Or: Device Settings → Apps → Default Apps → Digital Assistant
3. Select "OpenClaw Assistant"
4. Long press Home to activate

### 🔧 OpenClaw Configuration

This app uses OpenClaw's Chat Completions API. Ensure it's enabled in your `moltbot.json`:

```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": {
          "enabled": true
        }
      }
    }
  }
}
```

Then expose the gateway externally using ngrok or similar:

```bash
ngrok http 18789
```

In the app settings:
- **Server URL**: `https://<ngrok-subdomain>.ngrok-free.dev/v1/chat/completions`
- **Auth Token**: Your gateway auth token (from `gateway.auth.token` in `moltbot.json`)

#### Optional: Chat Completions Proxy

Newer OpenClaw versions may only return a status from webhooks instead of a full chat response. If you need full responses for the app (or for Apple Shortcuts), run the included proxy on your OpenClaw host so it translates requests and returns the expected format. See [android_proxy.js](android_proxy.js) (run with Node.js; recommended: manage with PM2, e.g. `pm2 start android_proxy.js --name "claw-proxy"`). Configure the app’s Webhook URL to point at the proxy (e.g. `http://<openclaw-host>:18790`) and set the token via `OPENCLAW_TOKEN` or in the script.

### 🛠 Tech Stack

- **UI**: Kotlin + Jetpack Compose + Material 3
- **Speech Recognition**: Android SpeechRecognizer
- **Text-to-Speech**: Android TextToSpeech
- **Wake Word Detection**: [Vosk](https://alphacephei.com/vosk/) (offline)
- **System Integration**: VoiceInteractionService
- **Networking**: OkHttp + Gson
- **Security**: EncryptedSharedPreferences

### 📋 Required Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Speech recognition & wake word detection |
| `INTERNET` | API communication |
| `FOREGROUND_SERVICE` | Always-on wake word detection |
| `POST_NOTIFICATIONS` | Status notifications |

### 🤝 Contributing

Pull Requests welcome! Feel free to report issues.

### 📄 License

MIT License - See [LICENSE](LICENSE) for details.

---

## 🤖 For AI Agents

This section is for AI agents (like OpenClaw) that need to set up integration.

### Setup Instructions

To configure OpenClaw for this Android app:

1. **Ensure Chat Completions API is enabled in `moltbot.json`:**
```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": { "enabled": true }
      }
    }
  }
}
```

2. **Expose gateway externally with ngrok:**
```bash
ngrok http 18789
```

3. **Provide to user:**
   - Server URL: `https://<ngrok-subdomain>.ngrok-free.dev/v1/chat/completions`
   - Auth Token: The gateway auth token (`gateway.auth.token` in `moltbot.json`)

### Request/Response Format

**Request (App → OpenClaw):**
```json
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer <gateway-auth-token>

{
  "model": "openclaw/voice-agent",
  "user": "session-uuid",
  "messages": [{"role": "user", "content": "User's spoken text"}]
}
```

**Response (OpenClaw → App):**
```json
{
  "id": "chatcmpl_...",
  "object": "chat.completion",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "AI response text"},
      "finish_reason": "stop"
    }
  ]
}
```

---

## 日本語

**あなたのAIアシスタントをポケットに** - OpenClaw専用のAndroid音声アシスタントアプリ

### ✨ 機能

- 🎤 **カスタマイズ可能なウェイクワード** - 「Open Claw」「Jarvis」「Computer」から選択、または自由入力
- 🏠 **ホームボタン長押し** - システムアシスタントとして動作
- 🔄 **連続会話モード** - セッションを維持して自然な対話
- 🔊 **音声読み上げ** - AIの応答を自動で読み上げ
- 💬 **In-App Chat** - テキスト＆音声のハイブリッド入力
- 🔒 **プライバシー重視** - 設定は暗号化保存
- 📴 **オフライン対応のウェイクワード検知** - Voskによるローカル処理

### 📱 使い方

1. **ホームボタン長押し** または **ウェイクワード** を話す
2. 質問やリクエストを話す
3. OpenClawが音声で応答
4. 会話を続ける（セッション維持）

### 🚀 セットアップ

#### 1. アプリのインストール

[Releases](https://github.com/yuga-hashimoto/OpenClawAssistant/releases) からAPKをダウンロード、またはソースからビルド。

#### 2. 設定

1. アプリを開く
2. 右上の⚙️から設定画面へ
3. 以下を入力：
   - **Webhook URL** (必須): OpenClawのエンドポイント
   - **Auth Token** (任意): Bearer認証用

#### 3. ウェイクワードの設定

1. 設定画面の「Wake Word」セクションを開く
2. プリセットから選択：
   - **Open Claw** (デフォルト)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (自由入力)
3. ホーム画面でWake Wordトグルをオンに

#### 4. システムアシスタントとして設定

1. アプリの「Home Button」カードをタップ
2. または: 端末の設定 → アプリ → デフォルトアプリ → デジタルアシスタント
3. 「OpenClaw Assistant」を選択
4. ホームボタン長押しで起動可能に

### 🔧 OpenClaw側の設定

OpenClawのChat Completions APIを使用します。`moltbot.json` で有効化を確認：

```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": {
          "enabled": true
        }
      }
    }
  }
}
```

ngrokなどでゲートウェイを外部公開：

```bash
ngrok http 18789
```

アプリの設定画面で：
- **Server URL**: `https://<ngrokサブドメイン>.ngrok-free.dev/v1/chat/completions`
- **Auth Token**: ゲートウェイ認証トークン（`moltbot.json` の `gateway.auth.token`）

### 🛠 技術スタック

- **UI**: Kotlin + Jetpack Compose + Material 3
- **音声認識**: Android SpeechRecognizer
- **音声合成**: Android TextToSpeech
- **ウェイクワード検知**: [Vosk](https://alphacephei.com/vosk/) (オフライン対応)
- **システム連携**: VoiceInteractionService
- **通信**: OkHttp + Gson
- **セキュリティ**: EncryptedSharedPreferences

### 📋 必要な権限

| 権限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 音声認識・ウェイクワード検知 |
| `INTERNET` | API通信 |
| `FOREGROUND_SERVICE` | Wake Word常時検知 |
| `POST_NOTIFICATIONS` | ステータス通知 |

### 🤝 Contributing

Pull Requests歓迎！Issues報告もお気軽に。

### 📄 ライセンス

MIT License - 詳細は [LICENSE](LICENSE) を参照。

---

## 🤖 AI向け情報

このセクションはOpenClawなどのAIエージェントが連携を設定するための情報です。

### 設定手順

1. **`moltbot.json` でChat Completions APIを有効化:**
```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "chatCompletions": { "enabled": true }
      }
    }
  }
}
```

2. **ngrokでゲートウェイを外部公開:**
```bash
ngrok http 18789
```

3. **ユーザーに伝える情報:**
   - Server URL: `https://<ngrokサブドメイン>.ngrok-free.dev/v1/chat/completions`
   - Auth Token: ゲートウェイ認証トークン（`moltbot.json` の `gateway.auth.token`）

### リクエスト/レスポンス形式

**リクエスト（アプリ → OpenClaw）:**
```json
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer <gateway-auth-token>

{
  "model": "openclaw/voice-agent",
  "user": "session-uuid",
  "messages": [{"role": "user", "content": "ユーザーの発話テキスト"}]
}
```

**レスポンス（OpenClaw → アプリ）:**
```json
{
  "id": "chatcmpl_...",
  "object": "chat.completion",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "AIの応答テキスト"},
      "finish_reason": "stop"
    }
  ]
}
```

---

Made with ❤️ for [OpenClaw](https://github.com/openclaw/openclaw)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)
