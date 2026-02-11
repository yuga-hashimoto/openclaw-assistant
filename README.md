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

Add a webhook for this app in your `openclaw.json`:

```json
{
  "hooks": {
    "voice": {
      "path": "/hooks/voice",
      "auth": {
        "bearer": "your-secret-token"
      }
    }
  }
}
```

Then expose it externally using ngrok or similar:

```bash
ngrok http 18080
```

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

This section is for AI agents (like OpenClaw) that need to set up webhook integration.

### Webhook Setup Instructions

To configure OpenClaw for this Android app:

1. **Add webhook to `openclaw.json`:**
```json
{
  "hooks": {
    "voice": {
      "path": "/hooks/voice",
      "auth": {
        "bearer": "<generate-secure-token>"
      }
    }
  }
}
```

2. **Expose externally with ngrok:**
```bash
ngrok http 18080
```

3. **Provide to user:**
   - Webhook URL: `https://<ngrok-subdomain>.ngrok.io/hooks/voice`
   - Auth Token: The bearer token you set (optional)

### Request/Response Format

**Request (App → OpenClaw):**
```json
POST /hooks/voice
Content-Type: application/json
Authorization: Bearer <token>

{
  "message": "User's spoken text",
  "session_id": "uuid-xxx-xxx"
}
```

**Response (OpenClaw → App):**
```json
{"response": "AI response text"}
```

Also accepts: `{"text": "..."}` or `{"message": "..."}`

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

`openclaw.json` にWebhookを追加：

```json
{
  "hooks": {
    "voice": {
      "path": "/hooks/voice",
      "auth": {
        "bearer": "your-secret-token"
      }
    }
  }
}
```

ngrokなどで外部公開：

```bash
ngrok http 18080
```

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

このセクションはOpenClawなどのAIエージェントがWebhook連携を設定するための情報です。

### Webhook設定手順

1. **`openclaw.json` にWebhookを追加:**
```json
{
  "hooks": {
    "voice": {
      "path": "/hooks/voice",
      "auth": {
        "bearer": "<安全なトークンを生成>"
      }
    }
  }
}
```

2. **ngrokで外部公開:**
```bash
ngrok http 18080
```

3. **ユーザーに伝える情報:**
   - Webhook URL: `https://<ngrok-subdomain>.ngrok.io/hooks/voice`
   - Auth Token: 設定したbearerトークン（任意）

### リクエスト/レスポンス形式

**リクエスト（アプリ → OpenClaw）:**
```json
POST /hooks/voice
Content-Type: application/json
Authorization: Bearer <token>

{
  "message": "ユーザーの発話テキスト",
  "session_id": "uuid-xxx-xxx"
}
```

**レスポンス（OpenClaw → アプリ）:**
```json
{"response": "AIの応答テキスト"}
```

`{"text": "..."}` や `{"message": "..."}` も対応。

---

Made with ❤️ for [OpenClaw](https://github.com/openclaw/openclaw)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)
