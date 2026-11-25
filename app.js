(() => {
  'use strict';

  const config = window.BERRY_VOICE_CONFIG || {};
  const apiBase = String(config.apiBase || '').replace(/\/+$/, '');
  const wsUrl = String(config.websocketUrl || '');
  const VOICE_RATE = 48000;
  const FRAME_SAMPLES = 960; // 20 ms at 48 kHz, matching the SVC Opus frame cadence.

  const form = document.getElementById('pair-form');
  const codeInput = document.getElementById('pair-code');
  const errorBox = document.getElementById('pair-error');
  const connectButton = document.getElementById('connect-btn');
  const pairView = document.getElementById('pair-view');
  const voiceView = document.getElementById('voice-view');
  const panelTitle = document.getElementById('panel-title');
  const playerName = document.getElementById('player-name');
  const roomSelect = document.getElementById('room-select');
  const muteButton = document.getElementById('mute-btn');
  const deafenButton = document.getElementById('deafen-btn');
  const disconnectButton = document.getElementById('disconnect-btn');
  const voiceStatus = document.getElementById('voice-status');

  let socket = null;
  let audioContext = null;
  let mediaStream = null;
  let micSource = null;
  let processor = null;
  let silentGain = null;
  let encoder = null;
  let muted = false;
  let deafened = false;
  let manuallyDisconnected = false;
  let nextMicTimestamp = 0;
  let sampleQueue = new Float32Array(0);
  const decoders = new Map();
  const lastSequence = new Map();

  const cleanCode = value => String(value || '').replace(/[^a-z0-9]/gi, '').toUpperCase().slice(0, 8);
  codeInput.addEventListener('input', () => {
    const clean = cleanCode(codeInput.value);
    if (clean !== codeInput.value) codeInput.value = clean;
    errorBox.textContent = '';
  });

  async function apiPost(path, body) {
    const response = await fetch(apiBase + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      body: JSON.stringify(body)
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `Erro HTTP ${response.status}`);
    return data;
  }

  function showVoice(name) {
    pairView.hidden = true;
    voiceView.hidden = false;
    panelTitle.textContent = 'Você está conectado';
    playerName.textContent = name || 'Jogador BerryMC';
  }

  function showPair() {
    pairView.hidden = false;
    voiceView.hidden = true;
    panelTitle.textContent = 'Vincular Minecraft';
  }

  function base64ToBytes(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function bytesToBase64(bytes) {
    let binary = '';
    const step = 0x8000;
    for (let i = 0; i < bytes.length; i += step) {
      binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + step, bytes.length)));
    }
    return btoa(binary);
  }

  function sendJson(value) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
  }

  async function createAudioContext() {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) throw new Error('Este navegador não oferece suporte ao áudio Web Audio. Use Chrome ou Edge atualizado.');
    try { audioContext = new Ctx({ sampleRate: VOICE_RATE }); }
    catch (_) { audioContext = new Ctx(); }
    await audioContext.resume();
  }

  async function ensureCodecSupport() {
    if (typeof AudioEncoder === 'undefined' || typeof AudioDecoder === 'undefined' || typeof AudioData === 'undefined' || typeof EncodedAudioChunk === 'undefined') {
      throw new Error('Seu navegador não oferece WebCodecs Opus. Use uma versão atualizada do Google Chrome ou Microsoft Edge.');
    }
    const encoderConfig = { codec: 'opus', sampleRate: VOICE_RATE, numberOfChannels: 1, bitrate: 24000 };
    const decoderConfig = { codec: 'opus', sampleRate: VOICE_RATE, numberOfChannels: 1 };
    const [enc, dec] = await Promise.all([
      AudioEncoder.isConfigSupported(encoderConfig),
      AudioDecoder.isConfigSupported(decoderConfig)
    ]);
    if (!enc.supported || !dec.supported) {
      throw new Error('O navegador não disponibilizou os codecs Opus necessários para conversar com o Simple Voice Chat. Use Chrome ou Edge atualizado.');
    }
  }

  async function connectWithToken(token, name) {
    if (!apiBase || !wsUrl) throw new Error('O relay Berry Voice ainda não está configurado.');
    if (socket) stopAudio();
    if (!audioContext || audioContext.state === 'closed') await createAudioContext();

    const activeSocket = new WebSocket(wsUrl);
    socket = activeSocket;
    activeSocket.onopen = () => {
      if (socket === activeSocket && activeSocket.readyState === WebSocket.OPEN) {
        activeSocket.send(JSON.stringify({ type: 'auth', token }));
      }
    };
    activeSocket.onmessage = async event => {
      if (socket !== activeSocket || typeof event.data !== 'string') return;
      let message;
      try { message = JSON.parse(event.data); } catch (_) { return; }
      switch (message.type) {
        case 'error':
          voiceStatus.textContent = message.message || 'Falha na conexão.';
          break;
        case 'ready':
          showVoice(message.player || name);
          await startMicrophone();
          break;
        case 'audio':
          if (message.opus) decodeAndPlay(message.speaker || 'unknown', Number(message.sequence || 0), message.opus);
          break;
        case 'groups':
          applyGroups(Array.isArray(message.items) ? message.items : []);
          break;
        case 'group_joined':
          voiceStatus.textContent = `Você entrou em: ${message.groupName || 'Proximidade'}`;
          break;
        case 'muted':
          muted = Boolean(message.muted);
          updateMuteButton();
          break;
        case 'status':
          resetPlaybackDecoders();
          voiceStatus.textContent = message.message || 'Atualizando a conexão de voz…';
          break;
        case 'pong':
          break;
        default:
          break;
      }
    };
    activeSocket.onerror = () => {
      if (socket === activeSocket) voiceStatus.textContent = 'Falha no WebSocket. Verifique o relay.berrymc.net.br.';
    };
    activeSocket.onclose = event => {
      if (socket !== activeSocket) return;
      socket = null;
      stopAudio(false);
      if (!manuallyDisconnected) {
        voiceStatus.textContent = event.reason || 'A conexão foi encerrada. Gere um novo código no Minecraft.';
        showPair();
      }
    };
  }

  function appendSamples(samples) {
    const combined = new Float32Array(sampleQueue.length + samples.length);
    combined.set(sampleQueue, 0);
    combined.set(samples, sampleQueue.length);
    sampleQueue = combined;
    while (sampleQueue.length >= FRAME_SAMPLES) {
      const frame = sampleQueue.slice(0, FRAME_SAMPLES);
      sampleQueue = sampleQueue.slice(FRAME_SAMPLES);
      encodeMicFrame(frame);
    }
  }

  function resampleTo48k(input, inputRate) {
    if (inputRate === VOICE_RATE) return new Float32Array(input);
    const outputLength = Math.max(1, Math.round(input.length * VOICE_RATE / inputRate));
    const output = new Float32Array(outputLength);
    const ratio = inputRate / VOICE_RATE;
    for (let i = 0; i < outputLength; i++) {
      const sourcePosition = i * ratio;
      const left = Math.min(input.length - 1, Math.floor(sourcePosition));
      const right = Math.min(input.length - 1, left + 1);
      const fraction = sourcePosition - left;
      output[i] = input[left] * (1 - fraction) + input[right] * fraction;
    }
    return output;
  }

  function encodeMicFrame(frame) {
    if (muted || !encoder || encoder.state !== 'configured' || !socket || socket.readyState !== WebSocket.OPEN) return;
    try {
      const audioData = new AudioData({
        format: 'f32-planar', sampleRate: VOICE_RATE, numberOfFrames: FRAME_SAMPLES,
        numberOfChannels: 1, timestamp: nextMicTimestamp, data: frame
      });
      nextMicTimestamp += 20_000;
      encoder.encode(audioData);
      audioData.close();
    } catch (error) {
      voiceStatus.textContent = 'Falha ao codificar o microfone: ' + (error.message || error);
    }
  }

  async function startMicrophone() {
    try {
      if (mediaStream && mediaStream.active && encoder && encoder.state === 'configured') {
        voiceStatus.textContent = 'Microfone ativo. Você está conectado ao Simple Voice Chat.';
        return;
      }
      if (mediaStream) {
        mediaStream.getTracks().forEach(track => track.stop());
        mediaStream = null;
      }
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        video: false
      });
      if (!audioContext || !socket || socket.readyState !== WebSocket.OPEN) {
        mediaStream.getTracks().forEach(track => track.stop());
        mediaStream = null;
        return;
      }

      encoder = new AudioEncoder({
        output: chunk => {
          if (muted || !socket || socket.readyState !== WebSocket.OPEN) return;
          const bytes = new Uint8Array(chunk.byteLength);
          chunk.copyTo(bytes);
          sendJson({ type: 'uplink', opus: bytesToBase64(bytes) });
        },
        error: error => { voiceStatus.textContent = 'Erro no codificador Opus: ' + (error.message || error); }
      });
      encoder.configure({ codec: 'opus', sampleRate: VOICE_RATE, numberOfChannels: 1, bitrate: 24000 });

      micSource = audioContext.createMediaStreamSource(mediaStream);
      processor = audioContext.createScriptProcessor(2048, 1, 1);
      silentGain = audioContext.createGain();
      silentGain.gain.value = 0;
      processor.onaudioprocess = event => {
        if (muted || !encoder || encoder.state !== 'configured') return;
        const input = event.inputBuffer.getChannelData(0);
        const resampled = resampleTo48k(input, audioContext.sampleRate);
        appendSamples(resampled);
        // Avoid an unbounded encoder queue if the browser/device is overloaded.
        if (encoder.encodeQueueSize > 12) {
          try { encoder.reset(); encoder.configure({ codec: 'opus', sampleRate: VOICE_RATE, numberOfChannels: 1, bitrate: 24000 }); }
          catch (_) { voiceStatus.textContent = 'O microfone está sobrecarregado. Tente novamente.'; }
        }
      };
      micSource.connect(processor);
      processor.connect(silentGain);
      silentGain.connect(audioContext.destination);
      voiceStatus.textContent = 'Microfone ativo. Você está conectado ao Simple Voice Chat.';
    } catch (error) {
      voiceStatus.textContent = 'Não foi possível acessar o microfone: ' + (error.message || error);
    }
  }

  function getDecoder(speaker) {
    if (decoders.has(speaker)) return decoders.get(speaker);
    let nextPlayTime = 0;
    const decoder = new AudioDecoder({
      output: audioData => {
        try {
          if (deafened || !audioContext || audioContext.state === 'closed') return;
          const frames = audioData.numberOfFrames;
          const channels = audioData.numberOfChannels;
          const buffer = audioContext.createBuffer(channels, frames, audioData.sampleRate);
          for (let channel = 0; channel < channels; channel++) {
            const samples = new Float32Array(frames);
            audioData.copyTo(samples, { planeIndex: channel, format: 'f32-planar' });
            buffer.copyToChannel(samples, channel);
          }
          const source = audioContext.createBufferSource();
          source.buffer = buffer;
          source.connect(audioContext.destination);
          const startAt = Math.max(audioContext.currentTime + 0.035, nextPlayTime);
          nextPlayTime = startAt + buffer.duration;
          source.start(startAt);
        } catch (error) {
          voiceStatus.textContent = 'Falha ao reproduzir áudio Opus: ' + (error.message || error);
        } finally { audioData.close(); }
      },
      error: error => { voiceStatus.textContent = 'Erro no decodificador Opus: ' + (error.message || error); }
    });
    decoder.configure({ codec: 'opus', sampleRate: VOICE_RATE, numberOfChannels: 1 });
    decoders.set(speaker, decoder);
    return decoder;
  }

  function resetPlaybackDecoders() {
    for (const decoder of decoders.values()) {
      try { if (decoder.state !== 'closed') decoder.close(); } catch (_) {}
    }
    decoders.clear();
    lastSequence.clear();
  }

  function decodeAndPlay(speaker, sequence, base64Opus) {
    if (deafened || !audioContext || audioContext.state === 'closed') return;
    try {
      let previous = lastSequence.get(speaker);
      if (previous !== undefined && sequence <= previous) {
        // AudioSender.reset() starts a fresh SVC sequence at zero. Drop small
        // out-of-order packets, but rebuild the decoder for a genuine restart.
        if (sequence === 0 || previous - sequence > 1000) {
          const oldDecoder = decoders.get(speaker);
          if (oldDecoder) { try { oldDecoder.close(); } catch (_) {} }
          decoders.delete(speaker);
          lastSequence.delete(speaker);
          previous = undefined;
        } else {
          return;
        }
      }
      lastSequence.set(speaker, sequence);
      const decoder = getDecoder(speaker);
      const bytes = base64ToBytes(base64Opus);
      // SVC packets are Opus frames. A 20 ms timestamp keeps decoder output ordered per speaker.
      const timestamp = sequence > 0 ? sequence * 20_000 : (previous || 0) * 20_000 + 20_000;
      decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp, data: bytes }));
    } catch (error) {
      voiceStatus.textContent = 'Não foi possível decodificar um pacote de voz: ' + (error.message || error);
    }
  }

  function applyGroups(groups) {
    const current = roomSelect.value || 'global';
    roomSelect.innerHTML = '';
    const proximity = document.createElement('option');
    proximity.value = 'global'; proximity.textContent = 'Proximidade';
    roomSelect.appendChild(proximity);
    for (const group of groups) {
      if (!group || !group.id) continue;
      const option = document.createElement('option');
      option.value = group.id;
      option.textContent = group.name || 'Grupo';
      roomSelect.appendChild(option);
    }
    roomSelect.value = Array.from(roomSelect.options).some(option => option.value === current) ? current : 'global';
  }

  function updateMuteButton() {
    muteButton.textContent = muted ? 'Ativar microfone' : 'Mutar microfone';
    muteButton.classList.toggle('active', muted);
  }

  function stopAudio(closeSocket = true) {
    if (processor) { try { processor.disconnect(); } catch (_) {} processor.onaudioprocess = null; processor = null; }
    if (micSource) { try { micSource.disconnect(); } catch (_) {} micSource = null; }
    if (silentGain) { try { silentGain.disconnect(); } catch (_) {} silentGain = null; }
    if (mediaStream) { mediaStream.getTracks().forEach(track => track.stop()); mediaStream = null; }
    if (encoder) { try { if (encoder.state !== 'closed') encoder.close(); } catch (_) {} encoder = null; }
    for (const decoder of decoders.values()) { try { if (decoder.state !== 'closed') decoder.close(); } catch (_) {} }
    decoders.clear(); lastSequence.clear();
    sampleQueue = new Float32Array(0); nextMicTimestamp = 0;
    if (audioContext) { audioContext.close().catch(() => {}); audioContext = null; }
    if (closeSocket && socket) { const previous = socket; socket = null; try { previous.close(1000, 'Desconectado pelo jogador'); } catch (_) {} }
  }

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const code = cleanCode(codeInput.value);
    codeInput.value = code;
    if (code.length !== 8) { errorBox.textContent = 'O código precisa ter exatamente 8 letras ou números.'; codeInput.focus(); return; }
    if (!window.isSecureContext) { errorBox.textContent = 'Abra o site em HTTPS para permitir o uso do microfone.'; return; }

    connectButton.disabled = true;
    connectButton.textContent = 'Validando código…';
    errorBox.textContent = '';
    manuallyDisconnected = false;
    stopAudio();
    try {
      // Start/resume the audio context during the click gesture, before HTTP/WS awaits.
      await createAudioContext();
      await ensureCodecSupport();
      const result = await apiPost('/api/pair/consume', { code });
      await connectWithToken(result.token, result.player);
    } catch (error) {
      errorBox.textContent = error.message || 'Não foi possível conectar.';
      stopAudio();
    } finally {
      connectButton.disabled = false;
      connectButton.innerHTML = 'Conectar ao Berry Voice <span>↗</span>';
    }
  });

  muteButton.addEventListener('click', () => {
    if (audioContext && audioContext.state === 'suspended') audioContext.resume().catch(() => {});
    muted = !muted;
    sendJson({ type: 'mute', muted });
    updateMuteButton();
  });

  deafenButton.addEventListener('click', () => {
    if (audioContext && audioContext.state === 'suspended') audioContext.resume().catch(() => {});
    deafened = !deafened;
    deafenButton.textContent = deafened ? 'Ativar áudio' : 'Silenciar áudio';
    deafenButton.classList.toggle('active', deafened);
  });

  roomSelect.addEventListener('change', () => {
    sendJson({ type: 'group_join', groupId: roomSelect.value || 'global' });
  });

  disconnectButton.addEventListener('click', () => {
    manuallyDisconnected = true;
    stopAudio();
    showPair();
    voiceStatus.textContent = 'Você se desconectou do Berry Voice.';
  });
})();
