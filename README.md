# OpenClaw Assistant 🦞
![CI](https://github.com/yuga-hashimoto/OpenClawAssistant/actions/workflows/ci.yml/badge.svg)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/R5R51S97C4)

**[日本語版はこちら](#日本語) | English below**

📹 **Demo Video**: https://x.com/i/status/2017914589938438532

---

## English

**Your AI Assistant in Your Pocket** - A dedicated Android voice assistant app for OpenClaw.

### ✨ Features

#### Voice & Speech
- 🎤 **Customizable Wake Word** - Choose from "Open Claw", "Hey Assistant", "Jarvis", "Computer", or set your own custom phrase
- 📴 **Offline Wake Word Detection** - Always-on local processing powered by [Vosk](https://alphacephei.com/vosk/), no internet required
- 🗣️ **Speech Recognition** - Real-time speech-to-text with partial results display and configurable silence timeout
- 🔊 **Text-to-Speech** - Automatic voice output with adjustable speech speed, multi-engine support, and smart text chunking for long responses
- 🔄 **Continuous Conversation Mode** - Auto-resumes listening after AI response for natural back-and-forth dialogue
- 🏠 **System Assistant Integration** - Long press Home button to activate via Android VoiceInteractionService

#### Chat & AI
- 💬 **In-App Chat Interface** - Full-featured chat UI with text and voice input, markdown rendering, and message timestamps
- 🤖 **Agent Selection** - Choose from multiple AI agents fetched dynamically from the gateway
- 📡 **Real-time Streaming** - See AI responses as they are generated via WebSocket gateway
- 💾 **Chat History** - Local message persistence with session management (create, switch, delete conversations)
- 🔔 **Thinking Sound** - Optional audio cue while waiting for AI response

#### Gateway & Connectivity
- 🌐 **WebSocket Gateway** - Persistent connection with auto-reconnect (exponential backoff), ping keep-alive, and RPC protocol
- 📋 **Agent Discovery** - Dynamically fetch available agents from the gateway
- 🔗 **Device Pairing** - Server-side device approval with Ed25519 cryptographic identity
- ✅ **Connection Testing** - Built-in connection test with live feedback in settings

#### System & Security
- 🔒 **Encrypted Settings** - All sensitive data (URL, tokens) stored with AES256-GCM encryption
- 🔑 **Device Identity** - Ed25519 key pair generation with Android Keystore integration
- 🚀 **Auto-Start on Boot** - Hotword service automatically resumes after device restart
- 📊 **Firebase Crashlytics** - Crash reporting with smart filtering of transient network errors
- 🔋 **Battery Optimization Exclusion** - Ensures wake word detection runs reliably in background

#### UI & Accessibility
- 🎨 **Material 3 Design** - Modern UI with Jetpack Compose and dynamic theming
- 📝 **Markdown Rendering** - Rich text display in chat messages (bold, italic, code blocks, lists, links)
- 🩺 **Voice Diagnostics** - Built-in health check for STT/TTS engines with fix suggestions
- ❓ **Troubleshooting Guide** - In-app help for common issues (Circle to Search, gesture navigation, etc.)
- 🌍 **Bilingual UI** - Full English and Japanese localization

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
4. Tap **Test Connection** to verify

#### 3. Wake Word Setup

1. Open "Wake Word" section in Settings
2. Choose a preset:
   - **Open Claw** (default)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (enter your own, 2-3 words)
3. Enable the Wake Word toggle on the home screen

#### 4. Set as System Assistant

1. Tap "Home Button" card in the app
2. Or: Device Settings → Apps → Default Apps → Digital Assistant
3. Select "OpenClaw Assistant"
4. Long press Home to activate

#### 5. Voice Settings (Optional)

- **Speech Speed**: Adjust TTS playback rate (default 1.2x)
- **TTS Engine**: Select from available engines on your device
- **Continuous Mode**: Enable auto-resume listening after response
- **Silence Timeout**: Configure how long to wait for speech input
- **Thinking Sound**: Toggle audio cue during AI processing
- **Default Agent**: Choose which AI agent handles your requests

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

### 🛠 Tech Stack

| Category | Technology |
|----------|-----------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **Speech Recognition** | Android SpeechRecognizer |
| **Text-to-Speech** | Android TextToSpeech (multi-engine) |
| **Wake Word** | [Vosk](https://alphacephei.com/vosk/) 0.3.75 (offline) |
| **System Integration** | VoiceInteractionService |
| **Networking** | OkHttp 4.12 + WebSocket |
| **JSON** | Gson |
| **Database** | Room (SQLite) |
| **Security** | EncryptedSharedPreferences (AES256-GCM) |
| **Cryptography** | Tink (Ed25519) + Android Keystore |
| **Markdown** | multiplatform-markdown-renderer-m3 |
| **Crash Reporting** | Firebase Crashlytics |
| **Analytics** | Firebase Analytics |
| **Min SDK** | Android 8.0 (API 26) |
| **Target SDK** | Android 14 (API 34) |

### 📋 Required Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Speech recognition & wake word detection |
| `INTERNET` | API communication |
| `FOREGROUND_SERVICE` | Always-on wake word detection |
| `FOREGROUND_SERVICE_MICROPHONE` | Microphone access in foreground service |
| `POST_NOTIFICATIONS` | Status notifications (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start hotword on boot |
| `WAKE_LOCK` | Keep CPU active during voice session |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reliable background wake word detection |

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

#### 音声・スピーチ
- 🎤 **カスタマイズ可能なウェイクワード** - 「Open Claw」「Hey Assistant」「Jarvis」「Computer」から選択、または自由にカスタムフレーズを入力
- 📴 **オフライン対応のウェイクワード検知** - [Vosk](https://alphacephei.com/vosk/)によるローカル処理で常時待ち受け、インターネット不要
- 🗣️ **音声認識** - リアルタイムの音声テキスト変換、部分認識結果の表示、サイレンスタイムアウト設定
- 🔊 **音声読み上げ (TTS)** - 読み上げ速度調整、複数エンジン対応、長文の自動分割読み上げ
- 🔄 **連続会話モード** - AI応答後に自動で聞き取り再開、自然な対話フロー
- 🏠 **システムアシスタント連携** - ホームボタン長押しでAndroid VoiceInteractionService経由で起動

#### チャット・AI
- 💬 **アプリ内チャットUI** - テキスト＆音声入力対応のフル機能チャット画面、Markdownレンダリング、タイムスタンプ表示
- 🤖 **エージェント選択** - ゲートウェイから動的に取得したAIエージェントを切り替え
- 📡 **リアルタイムストリーミング** - WebSocketゲートウェイによるAI応答のリアルタイム表示
- 💾 **チャット履歴** - ローカルDBでメッセージ永続化、セッション管理（作成・切替・削除）
- 🔔 **思考サウンド** - AI処理中のオプション音声フィードバック

#### ゲートウェイ・接続
- 🌐 **WebSocketゲートウェイ** - 自動再接続（指数バックオフ）、ping keep-alive、RPCプロトコル
- 📋 **エージェント自動取得** - ゲートウェイから利用可能なエージェントを動的取得
- 🔗 **デバイスペアリング** - Ed25519暗号鍵によるデバイス認証とサーバー側承認
- ✅ **接続テスト** - 設定画面で接続確認をリアルタイムフィードバック付きで実行

#### システム・セキュリティ
- 🔒 **設定の暗号化保存** - URL・トークンなどの機密データをAES256-GCM暗号化で保存
- 🔑 **デバイスID** - Ed25519キーペア生成とAndroid Keystore連携
- 🚀 **起動時の自動開始** - デバイス再起動後にホットワードサービスを自動復帰
- 📊 **Firebase Crashlytics** - クラッシュレポートと一時的なネットワークエラーのスマートフィルタリング
- 🔋 **バッテリー最適化除外** - バックグラウンドでのウェイクワード検知の安定動作を保証

#### UI・アクセシビリティ
- 🎨 **Material 3デザイン** - Jetpack ComposeとダイナミックテーマによるモダンUI
- 📝 **Markdownレンダリング** - チャットメッセージのリッチテキスト表示（太字、斜体、コードブロック、リスト、リンク）
- 🩺 **音声診断** - STT/TTSエンジンのヘルスチェックと修正提案
- ❓ **トラブルシューティングガイド** - よくある問題のアプリ内ヘルプ（Circle to Search、ジェスチャーナビゲーションなど）
- 🌍 **日英バイリンガルUI** - 英語と日本語の完全ローカライゼーション

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
4. **接続テスト**をタップして確認

#### 3. ウェイクワードの設定

1. 設定画面の「Wake Word」セクションを開く
2. プリセットから選択：
   - **Open Claw** (デフォルト)
   - **Hey Assistant**
   - **Jarvis**
   - **Computer**
   - **Custom...** (自由入力、2〜3語)
3. ホーム画面でWake Wordトグルをオンに

#### 4. システムアシスタントとして設定

1. アプリの「Home Button」カードをタップ
2. または: 端末の設定 → アプリ → デフォルトアプリ → デジタルアシスタント
3. 「OpenClaw Assistant」を選択
4. ホームボタン長押しで起動可能に

#### 5. 音声設定（任意）

- **読み上げ速度**: TTS再生速度を調整（デフォルト1.2倍）
- **TTSエンジン**: 端末上で利用可能なエンジンを選択
- **連続会話モード**: 応答後に自動で聞き取り再開
- **サイレンスタイムアウト**: 音声入力の待ち時間を設定
- **思考サウンド**: AI処理中の音声フィードバックの切替
- **デフォルトエージェント**: リクエストを処理するAIエージェントの選択

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

| カテゴリ | 技術 |
|---------|-----|
| **言語** | Kotlin |
| **UI** | Jetpack Compose + Material 3 |
| **音声認識** | Android SpeechRecognizer |
| **音声合成** | Android TextToSpeech (マルチエンジン対応) |
| **ウェイクワード** | [Vosk](https://alphacephei.com/vosk/) 0.3.75 (オフライン対応) |
| **システム連携** | VoiceInteractionService |
| **通信** | OkHttp 4.12 + WebSocket |
| **JSON** | Gson |
| **データベース** | Room (SQLite) |
| **セキュリティ** | EncryptedSharedPreferences (AES256-GCM) |
| **暗号** | Tink (Ed25519) + Android Keystore |
| **Markdown** | multiplatform-markdown-renderer-m3 |
| **クラッシュレポート** | Firebase Crashlytics |
| **アナリティクス** | Firebase Analytics |
| **最小SDK** | Android 8.0 (API 26) |
| **ターゲットSDK** | Android 14 (API 34) |

### 📋 必要な権限

| 権限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 音声認識・ウェイクワード検知 |
| `INTERNET` | API通信 |
| `FOREGROUND_SERVICE` | Wake Word常時検知 |
| `FOREGROUND_SERVICE_MICROPHONE` | フォアグラウンドサービスでのマイクアクセス |
| `POST_NOTIFICATIONS` | ステータス通知 (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | 起動時のホットワード自動開始 |
| `WAKE_LOCK` | 音声セッション中のCPU維持 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | バックグラウンドでのウェイクワード検知安定化 |

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
