(() => {
  'use strict';

  const OFFICIAL_VOICE_CLIENT = 'https://voice.byterider.xyz/';
  const form = document.getElementById('pair-form');
  const input = document.getElementById('pair-code');
  const error = document.getElementById('code-error');
  const handoff = document.getElementById('handoff-panel');
  const field = document.querySelector('.code-field-wrap');
  const submit = form.querySelector('button[type="submit"]');
  const originalButtonText = submit.querySelector('span').textContent;

  const sanitizeCode = (value) => value.replace(/\D/g, '').slice(0, 4);

  input.addEventListener('input', () => {
    const clean = sanitizeCode(input.value);
    if (input.value !== clean) input.value = clean;
    const valid = /^[0-9]{4}$/.test(clean);
    field.classList.toggle('is-valid', valid);
    error.textContent = '';
    handoff.hidden = true;
    submit.querySelector('span').textContent = originalButtonText;
  });

  async function copyCode(code) {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code);
      return true;
    }

    const temporary = document.createElement('textarea');
    temporary.value = code;
    temporary.setAttribute('readonly', '');
    temporary.style.position = 'fixed';
    temporary.style.opacity = '0';
    document.body.appendChild(temporary);
    temporary.select();
    let copied = false;
    try { copied = document.execCommand('copy'); } catch (_) { copied = false; }
    temporary.remove();
    return copied;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const code = sanitizeCode(input.value);

    if (!/^[0-9]{4}$/.test(code)) {
      error.textContent = 'Digite o código numérico exibido após usar /voice no Minecraft.';
      input.focus();
      return;
    }

    // Open from the direct click gesture so browser popup protection is less likely to block it.
    window.open(OFFICIAL_VOICE_CLIENT, '_blank', 'noopener,noreferrer');

    submit.disabled = true;
    submit.querySelector('span').textContent = 'Preparando seu código…';
    let copied = false;
    try { copied = await copyCode(code); } catch (_) { copied = false; }
    submit.disabled = false;

    if (copied) {
      handoff.hidden = false;
      submit.querySelector('span').textContent = 'Código copiado';
      error.textContent = '';
    } else {
      error.textContent = 'Não foi possível copiar automaticamente. Copie o código e abra o cliente oficial abaixo.';
      input.focus();
      input.select();
      submit.querySelector('span').textContent = 'Tentar copiar novamente';
    }
  });
})();
