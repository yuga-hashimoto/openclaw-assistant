/**
 * OpenClaw proxy for Chat Completions.
 *
 * Newer OpenClaw versions may return only a status from webhooks, not a full
 * response. This script acts as a proxy: it accepts requests from the
 * OpenClaw Assistant app (Android), forwards them to OpenClaw, and returns
 * the answer in the expected format.
 *
 * Can also be used with Apple Shortcuts using the same request/response style.
 *
 * Recommended: run on the OpenClaw host and manage with PM2, e.g.:
 *   pm2 start android_proxy.js --name "claw-proxy"
 *   OPENCLAW_TOKEN=your_token pm2 start android_proxy.js --name "claw-proxy"
 */
const http = require('http');

const CONFIG = {
    port: 18790,
    openclawUrl: 'http://127.0.0.1:18789/v1/chat/completions',
    token: process.env.OPENCLAW_TOKEN || 'YOUR_TOKEN_HERE'
};

const server = http.createServer((req, res) => {
    let body = '';

    req.on('data', chunk => {
        body += chunk;
    });

    req.on('end', async () => {
        try {
            const appData = JSON.parse(body);
            const userMessage = appData.message || appData.text || "";
            const sessionId = appData.session_id || "android-default-session";

            console.log(`[Proxy] App request from ${sessionId}: ${userMessage}`);

            const openAiPayload = JSON.stringify({
                model: "openclaw:main",
                messages: [
                    {
                        role: "system",
                        content: "Ohne Emojis antworten!!!"
                    },
                    {
                        role: "user",
                        content: userMessage
                    }
                ],
                user: sessionId,
                temperature: 0.7,
                stream: false
            });

            const postReq = http.request(CONFIG.openclawUrl, {
                method: 'POST',
                headers: {
                    'Authorization': CONFIG.token.startsWith('Bearer ') ? CONFIG.token : `Bearer ${CONFIG.token}`,
                    'Content-Type': 'application/json'
                }
            }, (ocRes) => {
                let ocData = '';
                ocRes.on('data', d => ocData += d);
                ocRes.on('end', () => {
                    try {
                        const json = JSON.parse(ocData);

                        if (json.error) {
                            console.error("[Proxy] OpenClaw API error:", json.error.message);
                            res.writeHead(200, { 'Content-Type': 'application/json' });
                            return res.end(JSON.stringify({ response: "Error: " + json.error.message }));
                        }

                        const aiText = json.choices && json.choices[0] ? json.choices[0].message.content : "No response received.";

                        const finalResponse = {
                            response: aiText
                        };

                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify(finalResponse));
                        console.log("[Proxy] Response sent to app successfully.");

                    } catch (e) {
                        console.error("[Proxy] OpenClaw response parse error:", ocData);
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ response: "Error processing AI response." }));
                    }
                });
            });

            postReq.on('error', (err) => {
                console.error("[Proxy] Network error to OpenClaw:", err.message);
                res.writeHead(500);
                res.end("OpenClaw unreachable.");
            });

            postReq.write(openAiPayload);
            postReq.end();

        } catch (e) {
            console.error("[Proxy] App request parse error:", e.message);
            res.writeHead(400);
            res.end("Invalid JSON");
        }
    });
});

server.listen(CONFIG.port, '0.0.0.0', () => {
    console.log(`Proxy listening on port ${CONFIG.port}.`);
});

process.on('uncaughtException', (err) => {
    console.error('[FATAL] Uncaught exception:', err);
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[FATAL] Unhandled rejection:', reason);
});
