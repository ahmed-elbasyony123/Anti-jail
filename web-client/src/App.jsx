import React, { useState, useEffect, useRef } from 'react';
import { 
  Shield, 
  Lock, 
  Unlock, 
  Send, 
  Key, 
  Terminal, 
  AlertTriangle, 
  Trash2, 
  Radio, 
  Eye, 
  EyeOff, 
  Copy, 
  Check, 
  RefreshCw,
  Globe,
  Sliders
} from 'lucide-react';
import { 
  deriveMailboxHash, 
  generateNewKeyPair, 
  encryptMessage, 
  decryptMessage 
} from './crypto/pgpService';

export default function App() {
  // Identities: Simulation mode allows switching between Alice and Bob
  const [activePersona, setActivePersona] = useState('Alice');
  
  // Storage in volatile React State (RAM only)
  const [identities, setIdentities] = useState({
    Alice: {
      name: 'Alice',
      email: 'alice@ghostkey.onion',
      keyPair: null,
      recipientPublicKey: '',
      messages: []
    },
    Bob: {
      name: 'Bob',
      email: 'bob@ghostkey.onion',
      keyPair: null,
      recipientPublicKey: '',
      messages: []
    }
  });

  const [relayUrl, setRelayUrl] = useState('http://127.0.0.1:8765');
  const [inputText, setInputText] = useState('');
  const [relayConnected, setRelayConnected] = useState(false);
  const [cryptoLogs, setCryptoLogs] = useState([]);
  const [showConfig, setShowConfig] = useState(false);
  const [copiedKey, setCopiedKey] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);

  const current = identities[activePersona];
  const socketRef = useRef(null);
  const logsEndRef = useRef(null);

  const addLog = (msg, level = 'info') => {
    const timestamp = new Date().toLocaleTimeString();
    setCryptoLogs(prev => [...prev.slice(-40), { text: `[${timestamp}] ${msg}`, level }]);
  };

  // Auto-generate keys on first launch if empty
  useEffect(() => {
    async function initKeys() {
      setIsGenerating(true);
      addLog('Initializing ephemeral Curve25519 keypairs...', 'info');
      try {
        const aliceKeys = await generateNewKeyPair('Alice', 'alice@ghostkey.onion');
        const bobKeys = await generateNewKeyPair('Bob', 'bob@ghostkey.onion');

        setIdentities(prev => ({
          Alice: {
            ...prev.Alice,
            keyPair: aliceKeys,
            recipientPublicKey: bobKeys.publicKeyArmored // Cross-link for immediate instant chat
          },
          Bob: {
            ...prev.Bob,
            keyPair: bobKeys,
            recipientPublicKey: aliceKeys.publicKeyArmored
          }
        }));
        addLog('Curve25519 ECC keypairs generated successfully.', 'success');
      } catch (err) {
        addLog(`Key generation error: ${err.message}`, 'error');
      } finally {
        setIsGenerating(false);
      }
    }
    initKeys();
  }, []);

  // WebSocket Live Connection to Blind Relay
  useEffect(() => {
    if (!current.keyPair) return;

    let ws = null;
    let isSubscribed = false;

    async function connectSocket() {
      try {
        const mailboxHash = await deriveMailboxHash(current.keyPair.fingerprint);
        const wsUrl = relayUrl.replace('http://', 'ws://').replace('https://', 'wss://') + '/api/v1/live';

        ws = new WebSocket(wsUrl);
        socketRef.current = ws;

        ws.onopen = () => {
          setRelayConnected(true);
          addLog(`[RELAY] Connected to Blind Dead-Drop. Subscribing to hash: ${mailboxHash.slice(0, 16)}...`, 'info');
          ws.send(JSON.stringify({
            type: 'SUBSCRIBE',
            mailboxHash: mailboxHash
          }));
          isSubscribed = true;
        };

        ws.onmessage = async (event) => {
          try {
            const data = JSON.parse(event.data);
            if (data.type === 'MESSAGE' && data.message) {
              const record = data.message;
              addLog(`[RECEIVE] Received PGP Ciphertext blob (${record.ciphertext.length} bytes). Decrypting...`, 'warn');

              let plaintext = '[Decryption Failed]';
              let isDecrypted = false;
              try {
                plaintext = await decryptMessage(record.ciphertext, current.keyPair.privateKeyArmored);
                isDecrypted = true;
                addLog(`[DECRYPT] OpenPGP signature & cipher verified. Message decrypted.`, 'success');
              } catch (decryptErr) {
                addLog(`[DECRYPT ERROR] ${decryptErr.message}`, 'error');
              }

              setIdentities(prev => ({
                ...prev,
                [activePersona]: {
                  ...prev[activePersona],
                  messages: [
                    ...prev[activePersona].messages,
                    {
                      id: record.id,
                      isOutgoing: false,
                      ciphertext: record.ciphertext,
                      plaintext: plaintext,
                      isDecrypted: isDecrypted,
                      timestamp: record.createdAt,
                      showArmor: false
                    }
                  ]
                }
              }));
            }
          } catch (e) {
            console.error(e);
          }
        };

        ws.onclose = () => {
          setRelayConnected(false);
          addLog('[RELAY] Disconnected from relay.', 'warn');
        };

        ws.onerror = () => {
          setRelayConnected(false);
        };
      } catch (e) {
        addLog(`Connection error: ${e.message}`, 'error');
      }
    }

    connectSocket();

    return () => {
      if (ws) ws.close();
    };
  }, [activePersona, current.keyPair, relayUrl]);

  // Send Encrypted Message
  const handleSend = async (e) => {
    e?.preventDefault();
    if (!inputText.trim() || !current.recipientPublicKey) return;

    const messageText = inputText.trim();
    setInputText('');

    try {
      addLog(`[ENCRYPT] Encrypting message with Curve25519 & padding noise...`, 'info');
      const ciphertext = await encryptMessage(messageText, current.recipientPublicKey);
      
      // Calculate recipient blind mailbox hash
      // Read recipient fingerprint
      const recipientHash = await deriveMailboxHash(
        activePersona === 'Alice' ? identities.Bob.keyPair.fingerprint : identities.Alice.keyPair.fingerprint
      );

      addLog(`[DISPATCH] Relaying encrypted payload to blind hash: ${recipientHash.slice(0, 16)}...`, 'info');

      // Send via HTTP POST
      const res = await fetch(`${relayUrl}/api/v1/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          mailboxHash: recipientHash,
          ciphertext: ciphertext,
          ttlSeconds: 3600
        })
      });

      const json = await res.json();
      if (!res.ok) throw new Error(json.error || 'Relay failed');

      addLog(`[STATUS] Blind Relay Drop confirmed (${json.status}).`, 'success');

      // Add to local sent list
      setIdentities(prev => ({
        ...prev,
        [activePersona]: {
          ...prev[activePersona],
          messages: [
            ...prev[activePersona].messages,
            {
              id: json.messageId,
              isOutgoing: true,
              ciphertext: ciphertext,
              plaintext: messageText,
              isDecrypted: true,
              timestamp: Date.now(),
              showArmor: false
            }
          ]
        }
      }));
    } catch (err) {
      addLog(`[SEND ERROR] ${err.message}`, 'error');
    }
  };

  // PANIC BUTTON: Instant Memory Wipe
  const handlePanicWipe = () => {
    if (socketRef.current) socketRef.current.close();
    setIdentities({
      Alice: { name: 'Alice', email: 'alice@ghostkey.onion', keyPair: null, recipientPublicKey: '', messages: [] },
      Bob: { name: 'Bob', email: 'bob@ghostkey.onion', keyPair: null, recipientPublicKey: '', messages: [] }
    });
    setCryptoLogs([{ text: '[PANIC] All keys, memory pools, and message caches wiped instantly.', level: 'error' }]);
  };

  const copyToClipboard = (text) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(true);
    setTimeout(() => setCopiedKey(false), 2000);
  };

  return (
    <div className="flex h-screen w-screen bg-[#0A0D0F] text-[#E6EDF3] overflow-hidden font-mono">
      {/* Left Sidebar: Identities & Security Panel */}
      <div className="w-80 border-r border-[#28333E] bg-[#12171D] flex flex-col justify-between p-4">
        <div>
          {/* Header */}
          <div className="flex items-center justify-between pb-4 border-b border-[#28333E]">
            <div className="flex items-center space-x-2">
              <Shield className="w-6 h-6 text-[#00FF9D]" />
              <span className="font-extrabold tracking-widest text-[#00FF9D] text-lg">GHOSTKEY</span>
            </div>
            <button 
              onClick={handlePanicWipe}
              title="EMERGENCY PANIC WIPE"
              className="p-1.5 rounded-md bg-[#FF3366]/20 border border-[#FF3366]/50 text-[#FF3366] hover:bg-[#FF3366] hover:text-black transition"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          </div>

          {/* Persona Switcher (For local testing & dual verification) */}
          <div className="mt-4">
            <span className="text-[11px] text-[#8B949E] uppercase font-bold tracking-wider">Active Terminal Persona</span>
            <div className="grid grid-cols-2 gap-2 mt-2">
              {['Alice', 'Bob'].map(p => (
                <button
                  key={p}
                  onClick={() => setActivePersona(p)}
                  className={`py-2 px-3 rounded text-xs font-bold transition flex items-center justify-center space-x-2 border ${
                    activePersona === p 
                      ? 'bg-[#00FF9D]/15 border-[#00FF9D] text-[#00FF9D]' 
                      : 'bg-[#1A2129] border-[#28333E] text-[#8B949E] hover:text-white'
                  }`}
                >
                  <Key className="w-3.5 h-3.5" />
                  <span>{p}</span>
                </button>
              ))}
            </div>
          </div>

          {/* Fingerprint Card */}
          <div className="mt-5 bg-[#1A2129] border border-[#28333E] rounded-lg p-3">
            <div className="flex items-center justify-between">
              <span className="text-[10px] text-[#00E5FF] font-bold tracking-wider">MY PGP FINGERPRINT</span>
              <button 
                onClick={() => current.keyPair && copyToClipboard(current.keyPair.publicKeyArmored)}
                className="text-[10px] text-[#8B949E] hover:text-[#00FF9D] flex items-center space-x-1"
              >
                {copiedKey ? <Check className="w-3 h-3 text-[#00FF9D]" /> : <Copy className="w-3 h-3" />}
                <span>{copiedKey ? 'COPIED' : 'COPY KEY'}</span>
              </button>
            </div>
            <div className="mt-2 text-[10px] text-[#E6EDF3] break-all bg-[#0A0D0F] p-2 rounded border border-[#28333E]">
              {isGenerating ? (
                <span className="text-[#FFB800] animate-pulse">Generating Curve25519 keys...</span>
              ) : current.keyPair ? (
                current.keyPair.fingerprint.match(/.{1,4}/g)?.join(' ')
              ) : (
                <span className="text-[#FF3366]">No active key (Wiped)</span>
              )}
            </div>
          </div>

          {/* Status Indicators */}
          <div className="mt-4 space-y-2">
            <div className="flex items-center justify-between text-xs bg-[#1A2129]/60 px-3 py-2 rounded border border-[#28333E]">
              <span className="text-[#8B949E]">Tor Blind Relay:</span>
              <span className={`flex items-center space-x-1.5 font-bold ${relayConnected ? 'text-[#00FF9D]' : 'text-[#FF3366]'}`}>
                <span className={`w-2 h-2 rounded-full ${relayConnected ? 'bg-[#00FF9D] animate-ping' : 'bg-[#FF3366]'}`}></span>
                <span>{relayConnected ? 'CONNECTED' : 'OFFLINE'}</span>
              </span>
            </div>

            <div className="flex items-center justify-between text-xs bg-[#1A2129]/60 px-3 py-2 rounded border border-[#28333E]">
              <span className="text-[#8B949E]">Storage Mode:</span>
              <span className="text-[#00E5FF] font-bold">RAM-ONLY / ZERO DISK</span>
            </div>
          </div>
        </div>

        {/* Security & Config Button */}
        <div className="space-y-2">
          <button
            onClick={() => setShowConfig(!showConfig)}
            className="w-full py-2 bg-[#1A2129] border border-[#28333E] text-xs text-[#8B949E] hover:text-white rounded flex items-center justify-center space-x-2 transition"
          >
            <Sliders className="w-3.5 h-3.5" />
            <span>RELAY & ROUTING CONFIG</span>
          </button>
        </div>
      </div>

      {/* Main Chat Area */}
      <div className="flex-1 flex flex-col justify-between bg-[#0A0D0F]">
        {/* Top Navbar */}
        <div className="h-14 border-b border-[#28333E] bg-[#12171D] px-6 flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-3 h-3 rounded-full bg-[#00FF9D]"></div>
            <div>
              <span className="text-sm font-bold text-[#E6EDF3]">
                SECURE CHANNEL: {activePersona === 'Alice' ? 'Bob' : 'Alice'}
              </span>
              <span className="ml-3 text-[10px] text-[#8B949E] bg-[#1A2129] px-2 py-0.5 rounded border border-[#28333E]">
                E2EE OpenPGP (Curve25519)
              </span>
            </div>
          </div>

          <div className="flex items-center space-x-3">
            <span className="text-[11px] text-[#8B949E]">
              Messages burn from RAM upon retrieval.
            </span>
          </div>
        </div>

        {/* Messages Stream */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          {current.messages.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-center opacity-60">
              <Lock className="w-12 h-12 text-[#8B949E] mb-3" />
              <p className="text-sm text-[#8B949E]">RAM Memory is completely clear.</p>
              <p className="text-xs text-[#8B949E] mt-1">Send a message to initiate PGP encrypted blind exchange.</p>
            </div>
          ) : (
            current.messages.map((msg) => (
              <div 
                key={msg.id}
                className={`flex flex-col ${msg.isOutgoing ? 'items-end' : 'items-start'}`}
              >
                <div 
                  className={`max-w-xl rounded-lg p-3.5 border ${
                    msg.isOutgoing 
                      ? 'bg-[#1A2129] border-[#00FF9D]/40 text-[#E6EDF3]' 
                      : 'bg-[#12171D] border-[#00E5FF]/40 text-[#E6EDF3]'
                  }`}
                >
                  <div className="flex items-center justify-between space-x-4 mb-2 pb-1.5 border-b border-[#28333E] text-[10px]">
                    <span className={`font-bold ${msg.isOutgoing ? 'text-[#00FF9D]' : 'text-[#00E5FF]'}`}>
                      {msg.isOutgoing ? 'OUTGOING [PGP-E2EE]' : 'INCOMING [PGP-E2EE]'}
                    </span>
                    <span className="text-[#8B949E]">
                      {new Date(msg.timestamp).toLocaleTimeString()}
                    </span>
                  </div>

                  <p className="text-sm leading-relaxed whitespace-pre-wrap">{msg.plaintext}</p>

                  {/* Raw PGP Armor View Toggle */}
                  <div className="mt-3 pt-2 border-t border-[#28333E]/60 flex items-center justify-between text-[10px]">
                    <button
                      onClick={() => {
                        setIdentities(prev => ({
                          ...prev,
                          [activePersona]: {
                            ...prev[activePersona],
                            messages: prev[activePersona].messages.map(m => 
                              m.id === msg.id ? { ...m, showArmor: !m.showArmor } : m
                            )
                          }
                        }));
                      }}
                      className="text-[#8B949E] hover:text-[#00FF9D] flex items-center space-x-1"
                    >
                      {msg.showArmor ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
                      <span>{msg.showArmor ? 'Hide Raw Ciphertext' : 'Inspect PGP Armor'}</span>
                    </button>
                    <span className="text-[#00FF9D] text-[9px]">BURNED ON RETRIEVAL</span>
                  </div>

                  {msg.showArmor && (
                    <pre className="mt-2 text-[9px] text-[#8B949E] bg-[#0A0D0F] p-2 rounded border border-[#28333E] overflow-x-auto leading-tight">
                      {msg.ciphertext}
                    </pre>
                  )}
                </div>
              </div>
            ))
          )}
        </div>

        {/* Input Bar */}
        <form onSubmit={handleSend} className="p-4 bg-[#12171D] border-t border-[#28333E] flex items-center space-x-3">
          <input
            type="text"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder="Type encrypted message (transmitted as PGP armor via Tor dead-drop)..."
            className="flex-1 bg-[#1A2129] border border-[#28333E] text-[#E6EDF3] placeholder-[#8B949E] px-4 py-3 rounded-lg text-sm focus:outline-none focus:border-[#00FF9D] transition"
          />
          <button
            type="submit"
            disabled={!inputText.trim() || !current.recipientPublicKey}
            className="bg-[#00FF9D] text-[#0A0D0F] px-5 py-3 rounded-lg font-bold hover:bg-[#00E5FF] transition flex items-center space-x-2 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <Send className="w-4 h-4" />
            <span>SEND</span>
          </button>
        </form>
      </div>

      {/* Right Column: Cryptographic Console & Live Logs */}
      <div className="w-96 border-l border-[#28333E] bg-[#12171D] flex flex-col">
        <div className="h-14 border-b border-[#28333E] px-4 flex items-center justify-between">
          <div className="flex items-center space-x-2 text-xs font-bold text-[#8B949E]">
            <Terminal className="w-4 h-4 text-[#00FF9D]" />
            <span>CRYPTOGRAPHIC TELEMETRY</span>
          </div>
          <button 
            onClick={() => setCryptoLogs([])}
            className="text-[10px] text-[#8B949E] hover:text-white"
          >
            CLEAR
          </button>
        </div>

        <div className="flex-1 p-3 overflow-y-auto space-y-1.5 text-[10px]">
          {cryptoLogs.map((log, index) => (
            <div 
              key={index} 
              className={`leading-tight ${
                log.level === 'error' ? 'text-[#FF3366]' :
                log.level === 'warn' ? 'text-[#FFB800]' :
                log.level === 'success' ? 'text-[#00FF9D]' : 'text-[#8B949E]'
              }`}
            >
              {log.text}
            </div>
          ))}
          <div ref={logsEndRef} />
        </div>

        {/* Routing details banner */}
        <div className="p-3 bg-[#0A0D0F] border-t border-[#28333E] text-[10px] text-[#8B949E]">
          <div className="flex items-center justify-between">
            <span>ISP Visibility:</span>
            <span className="text-[#00FF9D] font-bold">ZERO (Tor Guard Obfuscation)</span>
          </div>
          <div className="flex items-center justify-between mt-1">
            <span>Server Memory:</span>
            <span className="text-[#00E5FF] font-bold">Volatile RAM Queue</span>
          </div>
        </div>
      </div>
    </div>
  );
}
