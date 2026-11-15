'use strict';

/* ============================================================
   NEON VOID — a neon arcade space shooter
   Pure canvas 2D. No dependencies, no assets, no build step.
   ============================================================ */

// ---------- Canvas setup ----------
const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');
let W = 0, H = 0;

function resize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  W = window.innerWidth;
  H = window.innerHeight;
  canvas.width = Math.floor(W * dpr);
  canvas.height = Math.floor(H * dpr);
  canvas.style.width = W + 'px';
  canvas.style.height = H + 'px';
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}
window.addEventListener('resize', resize);
resize();

// ---------- Audio (synthesized, no files) ----------
let audioCtx = null;
let muted = false;

function ensureAudio() {
  if (!audioCtx) {
    try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { /* no audio */ }
  }
  if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
}

function beep(freq, dur, type, vol, slideTo) {
  if (!audioCtx || muted) return;
  const t = audioCtx.currentTime;
  const osc = audioCtx.createOscillator();
  const gain = audioCtx.createGain();
  osc.type = type || 'square';
  osc.frequency.setValueAtTime(freq, t);
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(Math.max(20, slideTo), t + dur);
  gain.gain.setValueAtTime(vol || 0.12, t);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  osc.connect(gain);
  gain.connect(audioCtx.destination);
  osc.start(t);
  osc.stop(t + dur);
}

function noiseBurst(dur, vol, cutoff) {
  if (!audioCtx || muted) return;
  const t = audioCtx.currentTime;
  const len = Math.floor(audioCtx.sampleRate * dur);
  const buffer = audioCtx.createBuffer(1, len, audioCtx.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len);
  const src = audioCtx.createBufferSource();
  src.buffer = buffer;
  const filter = audioCtx.createBiquadFilter();
  filter.type = 'lowpass';
  filter.frequency.value = cutoff || 1200;
  const gain = audioCtx.createGain();
  gain.gain.setValueAtTime(vol || 0.25, t);
  gain.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  src.connect(filter);
  filter.connect(gain);
  gain.connect(audioCtx.destination);
  src.start(t);
}

const sfx = {
  shoot()   { beep(880 + Math.random() * 120, 0.07, 'square', 0.05, 320); },
  boom()    { noiseBurst(0.35, 0.3, 900); beep(160, 0.3, 'sawtooth', 0.12, 40); },
  bigBoom() { noiseBurst(0.6, 0.45, 600); beep(110, 0.5, 'sawtooth', 0.2, 30); },
  hit()     { noiseBurst(0.4, 0.4, 700); beep(220, 0.35, 'sawtooth', 0.2, 50); },
  power()   { beep(520, 0.09, 'square', 0.12); setTimeout(() => beep(780, 0.09, 'square', 0.12), 70); setTimeout(() => beep(1040, 0.14, 'square', 0.12), 140); },
  wave()    { beep(392, 0.12, 'triangle', 0.16); setTimeout(() => beep(523, 0.12, 'triangle', 0.16), 110); setTimeout(() => beep(659, 0.2, 'triangle', 0.16), 220); },
  enemyHit(){ beep(300 + Math.random() * 80, 0.05, 'square', 0.04, 180); }
};

// ---------- DOM refs ----------
const hudEl = document.getElementById('hud');
const scoreEl = document.getElementById('score');
const multEl = document.getElementById('multiplier');
const waveEl = document.getElementById('wave-label');
const livesEl = document.getElementById('lives');
const hiscoreEl = document.getElementById('hiscore');
const titleScreen = document.getElementById('title-screen');
const pauseScreen = document.getElementById('pause-screen');
const gameoverScreen = document.getElementById('gameover-screen');
const finalScoreEl = document.getElementById('final-score');
const newRecordEl = document.getElementById('new-record');
const gameoverHiscoreEl = document.getElementById('gameover-hiscore');
const titleHiscoreEl = document.getElementById('title-hiscore');
const muteBtn = document.getElementById('mute-btn');
const pauseBtn = document.getElementById('pause-btn');

// ---------- Game state ----------
const STATE = { TITLE: 'title', PLAYING: 'playing', PAUSED: 'paused', OVER: 'over' };
let state = STATE.TITLE;

let score = 0;
let hiScore = 0;
try { hiScore = parseInt(localStorage.getItem('neonvoid-hiscore') || '0', 10) || 0; } catch (e) { hiScore = 0; }
let wave = 0;
let combo = 0;          // consecutive kills
let comboTimer = 0;     // time left before combo resets
let shake = 0;          // screen shake magnitude
let bannerText = '';
let bannerTimer = 0;
let waveDelay = 0;      // countdown to next wave
let spawnQueue = [];
let spawnTimer = 0;
let spawnInterval = 1;
let elapsed = 0;

const player = {
  x: 0, y: 0, r: 14,
  speed: 340,
  lives: 3,
  weapon: 1,            // 1 = single, 2 = double, 3 = triple
  rapidTimer: 0,        // rapid fire remaining
  fireTimer: 0,
  shield: false,
  invulnTimer: 0,
  tilt: 0
};

let bullets = [];       // player bullets
let enemyBullets = [];
let enemies = [];
let particles = [];
let powerups = [];
let stars = [];

// ---------- Input ----------
const keys = {};
let pointerActive = false;
let pointerX = 0, pointerY = 0;

window.addEventListener('keydown', (e) => {
  keys[e.key.toLowerCase()] = true;
  if (['arrowup', 'arrowdown', 'arrowleft', 'arrowright', ' '].includes(e.key.toLowerCase())) e.preventDefault();
  const k = e.key.toLowerCase();
  if (k === 'm') toggleMute();
  if ((k === 'p' || k === 'escape') && state === STATE.PLAYING) pauseGame();
  else if ((k === 'p' || k === 'escape') && state === STATE.PAUSED) resumeGame();
  if ((k === 'enter' || k === ' ') && state === STATE.TITLE) startGame();
  if ((k === 'enter' || k === ' ') && state === STATE.OVER) startGame();
});
window.addEventListener('keyup', (e) => { keys[e.key.toLowerCase()] = false; });

function setPointer(e) {
  pointerX = e.clientX;
  pointerY = e.clientY - 80; // offset so finger doesn't cover the ship
}
canvas.addEventListener('pointerdown', (e) => { pointerActive = true; setPointer(e); ensureAudio(); });
window.addEventListener('pointermove', (e) => { if (pointerActive) setPointer(e); });
window.addEventListener('pointerup', () => { pointerActive = false; });
window.addEventListener('pointercancel', () => { pointerActive = false; });
canvas.addEventListener('touchmove', (e) => e.preventDefault(), { passive: false });

document.addEventListener('visibilitychange', () => {
  if (document.hidden && state === STATE.PLAYING) pauseGame();
});

// ---------- Buttons ----------
document.getElementById('start-btn').addEventListener('click', () => { ensureAudio(); startGame(); });
document.getElementById('play-again-btn').addEventListener('click', () => { ensureAudio(); startGame(); });
document.getElementById('resume-btn').addEventListener('click', resumeGame);
document.getElementById('restart-btn').addEventListener('click', () => { ensureAudio(); startGame(); });
pauseBtn.addEventListener('click', () => {
  if (state === STATE.PLAYING) pauseGame();
  else if (state === STATE.PAUSED) resumeGame();
});
muteBtn.addEventListener('click', toggleMute);

function toggleMute() {
  muted = !muted;
  muteBtn.textContent = muted ? '🔇' : '🔊';
}

// ---------- Helpers ----------
function rand(min, max) { return min + Math.random() * (max - min); }
function dist2(ax, ay, bx, by) { const dx = ax - bx, dy = ay - by; return dx * dx + dy * dy; }

function initStars() {
  stars = [];
  for (let i = 0; i < 110; i++) {
    stars.push({
      x: Math.random() * W,
      y: Math.random() * H,
      speed: rand(20, 160),
      size: rand(0.6, 2.4),
      tw: rand(0, Math.PI * 2)
    });
  }
}
initStars();
window.addEventListener('resize', initStars);

// ---------- Game flow ----------
function startGame() {
  score = 0;
  combo = 0;
  comboTimer = 0;
  wave = 0;
  shake = 0;
  bullets = [];
  enemyBullets = [];
  enemies = [];
  particles = [];
  powerups = [];
  player.x = W / 2;
  player.y = H - 110;
  player.lives = 3;
  player.weapon = 1;
  player.rapidTimer = 0;
  player.shield = false;
  player.invulnTimer = 2;
  player.fireTimer = 0;
  waveDelay = 0.8;
  state = STATE.PLAYING;
  titleScreen.classList.add('hidden');
  pauseScreen.classList.add('hidden');
  gameoverScreen.classList.add('hidden');
  hudEl.classList.remove('hidden');
  updateHUD();
}

function pauseGame() {
  if (state !== STATE.PLAYING) return;
  state = STATE.PAUSED;
  pauseScreen.classList.remove('hidden');
}

function resumeGame() {
  if (state !== STATE.PAUSED) return;
  state = STATE.PLAYING;
  pauseScreen.classList.add('hidden');
  ensureAudio();
}

function gameOver() {
  state = STATE.OVER;
  const isRecord = score > hiScore;
  if (isRecord) {
    hiScore = score;
    try { localStorage.setItem('neonvoid-hiscore', String(hiScore)); } catch (e) { /* ignore */ }
  }
  finalScoreEl.textContent = score.toLocaleString();
  newRecordEl.classList.toggle('hidden', !isRecord);
  gameoverHiscoreEl.textContent = 'HIGH SCORE  ' + hiScore.toLocaleString();
  hudEl.classList.add('hidden');
  gameoverScreen.classList.remove('hidden');
  sfx.bigBoom();
}

function startWave(n) {
  wave = n;
  spawnQueue = [];
  const count = 6 + n * 3;
  for (let i = 0; i < count; i++) {
    const r = Math.random();
    let type = 'drifter';
    if (n >= 2 && r < 0.28) type = 'weaver';
    else if (n >= 3 && r < 0.48) type = 'chaser';
    else if (n >= 4 && r < 0.58) type = 'tank';
    spawnQueue.push(type);
  }
  spawnTimer = 0.3;
  spawnInterval = Math.max(0.35, 1.15 - n * 0.08);
  bannerText = 'WAVE ' + n;
  bannerTimer = 2;
  sfx.wave();
  updateHUD();
}

function spawnEnemy(type) {
  const x = rand(40, W - 40);
  const base = { x, y: -40, t: 0, shootTimer: rand(1, 2.5), flash: 0 };
  if (type === 'drifter') {
    enemies.push(Object.assign(base, { type, r: 16, hp: 1, maxHp: 1, vy: rand(90, 150), sway: rand(30, 90), swayPhase: rand(0, Math.PI * 2), score: 50, color: '#ff4dd8' }));
  } else if (type === 'weaver') {
    enemies.push(Object.assign(base, { type, r: 14, hp: 2, maxHp: 2, vy: rand(170, 220), sway: rand(90, 150), swayPhase: rand(0, Math.PI * 2), score: 80, color: '#00f0ff' }));
  } else if (type === 'chaser') {
    enemies.push(Object.assign(base, { type, r: 15, hp: 3, maxHp: 3, vy: rand(95, 125), score: 120, color: '#ff9f1c' }));
  } else { // tank
    enemies.push(Object.assign(base, { type, r: 30, hp: 10 + wave, maxHp: 10 + wave, vy: rand(38, 55), score: 300, color: '#b14dff' }));
  }
}

function spawnPowerup(x, y) {
  const r = Math.random();
  let kind;
  if (r < 0.38) kind = 'P';
  else if (r < 0.62) kind = 'R';
  else if (r < 0.82) kind = 'S';
  else kind = 'L';
  powerups.push({ x, y, kind, r: 13, t: 0 });
}

function explode(x, y, color, count, power) {
  for (let i = 0; i < count; i++) {
    const a = Math.random() * Math.PI * 2;
    const sp = rand(40, power || 260);
    particles.push({
      x, y,
      vx: Math.cos(a) * sp,
      vy: Math.sin(a) * sp,
      life: rand(0.3, 0.9),
      maxLife: 0.9,
      size: rand(1.5, 4),
      color
    });
  }
}

function addScore(base) {
  combo++;
  comboTimer = 3;
  const mult = Math.min(8, 1 + Math.floor(combo / 5));
  score += base * mult;
  updateHUD();
}

function updateHUD() {
  scoreEl.textContent = score.toLocaleString();
  const mult = Math.min(8, 1 + Math.floor(combo / 5));
  multEl.textContent = mult > 1 ? '×' + mult : '';
  waveEl.textContent = 'WAVE ' + Math.max(1, wave);
  livesEl.textContent = '♥'.repeat(Math.max(0, player.lives));
  hiscoreEl.textContent = 'HI ' + Math.max(hiScore, score).toLocaleString();
}

// ---------- Update ----------
function update(dt) {
  elapsed += dt;

  // Stars always scroll (nice backdrop on title screen too)
  for (const s of stars) {
    s.y += s.speed * dt * (state === STATE.PLAYING ? 1 : 0.35);
    s.tw += dt * 3;
    if (s.y > H + 4) { s.y = -4; s.x = Math.random() * W; }
  }

  if (state !== STATE.PLAYING) return;

  // --- Player movement ---
  let mx = 0, my = 0;
  if (keys['a'] || keys['arrowleft']) mx -= 1;
  if (keys['d'] || keys['arrowright']) mx += 1;
  if (keys['w'] || keys['arrowup']) my -= 1;
  if (keys['s'] || keys['arrowdown']) my += 1;
  if (mx !== 0 || my !== 0) {
    const len = Math.hypot(mx, my);
    player.x += (mx / len) * player.speed * dt;
    player.y += (my / len) * player.speed * dt;
    player.tilt = mx * 0.25;
  } else if (pointerActive) {
    const dx = pointerX - player.x;
    const dy = pointerY - player.y;
    const d = Math.hypot(dx, dy);
    if (d > 4) {
      const step = Math.min(d, player.speed * 1.4 * dt);
      player.x += (dx / d) * step;
      player.y += (dy / d) * step;
      player.tilt = Math.max(-0.3, Math.min(0.3, dx * 0.004));
    }
  } else {
    player.tilt *= 0.85;
  }
  player.x = Math.max(player.r, Math.min(W - player.r, player.x));
  player.y = Math.max(player.r, Math.min(H - player.r, player.y));

  // --- Firing (automatic) ---
  player.fireTimer -= dt;
  const fireRate = player.rapidTimer > 0 ? 0.075 : 0.15;
  if (player.fireTimer <= 0) {
    player.fireTimer = fireRate;
    const bx = player.x, by = player.y - player.r - 4;
    if (player.weapon === 1) {
      bullets.push({ x: bx, y: by, vx: 0, vy: -560, r: 4 });
    } else if (player.weapon === 2) {
      bullets.push({ x: bx - 8, y: by, vx: 0, vy: -560, r: 4 });
      bullets.push({ x: bx + 8, y: by, vx: 0, vy: -560, r: 4 });
    } else {
      bullets.push({ x: bx, y: by, vx: 0, vy: -560, r: 4 });
      bullets.push({ x: bx - 10, y: by + 4, vx: -70, vy: -540, r: 4 });
      bullets.push({ x: bx + 10, y: by + 4, vx: 70, vy: -540, r: 4 });
    }
    sfx.shoot();
  }
  if (player.rapidTimer > 0) player.rapidTimer -= dt;
  if (player.invulnTimer > 0) player.invulnTimer -= dt;

  // Engine trail particles
  if (Math.random() < 0.6) {
    particles.push({
      x: player.x + rand(-3, 3), y: player.y + player.r,
      vx: rand(-15, 15), vy: rand(60, 120),
      life: rand(0.2, 0.45), maxLife: 0.45,
      size: rand(1.5, 3), color: '#00f0ff'
    });
  }

  // --- Waves / spawning ---
  if (waveDelay > 0) {
    waveDelay -= dt;
    if (waveDelay <= 0) startWave(wave + 1);
  } else if (spawnQueue.length > 0) {
    spawnTimer -= dt;
    if (spawnTimer <= 0) {
      spawnTimer = spawnInterval * rand(0.6, 1.3);
      spawnEnemy(spawnQueue.shift());
    }
  } else if (enemies.length === 0) {
    waveDelay = 2.2;
  }

  // --- Combo decay ---
  if (comboTimer > 0) {
    comboTimer -= dt;
    if (comboTimer <= 0) { combo = 0; updateHUD(); }
  }

  // --- Bullets ---
  for (let i = bullets.length - 1; i >= 0; i--) {
    const b = bullets[i];
    b.x += b.vx * dt;
    b.y += b.vy * dt;
    if (b.y < -20 || b.x < -20 || b.x > W + 20) bullets.splice(i, 1);
  }
  for (let i = enemyBullets.length - 1; i >= 0; i--) {
    const b = enemyBullets[i];
    b.x += b.vx * dt;
    b.y += b.vy * dt;
    if (b.y < -20 || b.y > H + 20 || b.x < -20 || b.x > W + 20) enemyBullets.splice(i, 1);
  }

  // --- Enemies ---
  for (let i = enemies.length - 1; i >= 0; i--) {
    const e = enemies[i];
    e.t += dt;
    if (e.flash > 0) e.flash -= dt;
    e.y += e.vy * dt;

    if (e.type === 'drifter' || e.type === 'weaver') {
      e.x += Math.sin(e.t * 2.2 + e.swayPhase) * e.sway * dt;
    } else if (e.type === 'chaser') {
      const dx = player.x - e.x;
      const d = Math.hypot(dx, 1);
      e.x += (dx / d) * 70 * dt;
    }
    e.x = Math.max(e.r, Math.min(W - e.r, e.x));

    // Enemy shooting
    if ((e.type === 'weaver' || e.type === 'tank') && e.y > 0 && e.y < H * 0.7) {
      e.shootTimer -= dt;
      if (e.shootTimer <= 0) {
        e.shootTimer = e.type === 'tank' ? rand(1.1, 1.8) : rand(1.8, 2.8);
        const dx = player.x - e.x, dy = player.y - e.y;
        const d = Math.hypot(dx, dy) || 1;
        const sp = 210;
        enemyBullets.push({ x: e.x, y: e.y + e.r, vx: (dx / d) * sp, vy: (dy / d) * sp, r: 5 });
      }
    }

    if (e.y > H + 60) enemies.splice(i, 1);
  }

  // --- Powerups ---
  for (let i = powerups.length - 1; i >= 0; i--) {
    const p = powerups[i];
    p.t += dt;
    p.y += 70 * dt;
    p.x += Math.sin(p.t * 3) * 25 * dt;
    if (p.y > H + 30) powerups.splice(i, 1);
  }

  // --- Particles ---
  for (let i = particles.length - 1; i >= 0; i--) {
    const p = particles[i];
    p.life -= dt;
    if (p.life <= 0) { particles.splice(i, 1); continue; }
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    p.vx *= 0.98;
    p.vy *= 0.98;
  }

  // --- Collisions: player bullets vs enemies ---
  for (let i = bullets.length - 1; i >= 0; i--) {
    const b = bullets[i];
    let consumed = false;
    for (let j = enemies.length - 1; j >= 0; j--) {
      const e = enemies[j];
      if (dist2(b.x, b.y, e.x, e.y) < (b.r + e.r) * (b.r + e.r)) {
        consumed = true;
        e.hp--;
        e.flash = 0.08;
        explode(b.x, b.y, '#ffffff', 3, 120);
        if (e.hp <= 0) {
          enemies.splice(j, 1);
          addScore(e.score);
          explode(e.x, e.y, e.color, e.type === 'tank' ? 46 : 22, e.type === 'tank' ? 380 : 260);
          if (e.type === 'tank') { sfx.bigBoom(); shake = Math.min(shake + 10, 18); }
          else sfx.boom();
          if (Math.random() < 0.16) spawnPowerup(e.x, e.y);
        } else {
          sfx.enemyHit();
        }
        break;
      }
    }
    if (consumed) bullets.splice(i, 1);
  }

  // --- Collisions: enemy bullets vs player ---
  if (player.invulnTimer <= 0) {
    for (let i = enemyBullets.length - 1; i >= 0; i--) {
      const b = enemyBullets[i];
      if (dist2(b.x, b.y, player.x, player.y) < (b.r + player.r - 4) * (b.r + player.r - 4)) {
        enemyBullets.splice(i, 1);
        damagePlayer();
        break;
      }
    }
  }

  // --- Collisions: enemies vs player (ram) ---
  if (player.invulnTimer <= 0) {
    for (let j = enemies.length - 1; j >= 0; j--) {
      const e = enemies[j];
      if (dist2(e.x, e.y, player.x, player.y) < (e.r + player.r - 4) * (e.r + player.r - 4)) {
        explode(e.x, e.y, e.color, 24, 300);
        enemies.splice(j, 1);
        damagePlayer();
        break;
      }
    }
  }

  // --- Collisions: powerups vs player ---
  for (let i = powerups.length - 1; i >= 0; i--) {
    const p = powerups[i];
    if (dist2(p.x, p.y, player.x, player.y) < (p.r + player.r + 6) * (p.r + player.r + 6)) {
      applyPowerup(p.kind);
      powerups.splice(i, 1);
    }
  }

  if (bannerTimer > 0) bannerTimer -= dt;
  if (shake > 0) shake = Math.max(0, shake - dt * 30);
}

function damagePlayer() {
  if (player.shield) {
    player.shield = false;
    player.invulnTimer = 1.5;
    explode(player.x, player.y, '#4da6ff', 20, 280);
    shake = Math.min(shake + 6, 14);
    sfx.hit();
    updateHUD();
    return;
  }
  player.lives--;
  player.weapon = Math.max(1, player.weapon - 1);
  player.invulnTimer = 2.5;
  explode(player.x, player.y, '#ff5d7a', 34, 340);
  shake = Math.min(shake + 12, 20);
  sfx.hit();
  updateHUD();
  if (player.lives <= 0) {
    explode(player.x, player.y, '#00f0ff', 60, 460);
    gameOver();
  }
}

function applyPowerup(kind) {
  sfx.power();
  if (kind === 'P') player.weapon = Math.min(3, player.weapon + 1);
  else if (kind === 'R') player.rapidTimer = 10;
  else if (kind === 'S') player.shield = true;
  else if (kind === 'L') player.lives = Math.min(5, player.lives + 1);
  explode(player.x, player.y, '#ffffff', 10, 160);
  updateHUD();
}

// ---------- Render ----------
function draw() {
  ctx.clearRect(0, 0, W, H);

  // Background gradient
  const grad = ctx.createLinearGradient(0, 0, 0, H);
  grad.addColorStop(0, '#0a0620');
  grad.addColorStop(0.5, '#050510');
  grad.addColorStop(1, '#0d0a2a');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, W, H);

  ctx.save();
  if (shake > 0) {
    ctx.translate(rand(-shake, shake), rand(-shake, shake));
  }

  // Stars
  for (const s of stars) {
    const a = 0.35 + 0.65 * Math.abs(Math.sin(s.tw));
    ctx.globalAlpha = a;
    ctx.fillStyle = '#cfe8ff';
    ctx.fillRect(s.x, s.y, s.size, s.size * 2.2);
  }
  ctx.globalAlpha = 1;

  // Powerups
  for (const p of powerups) {
    const colors = { P: '#3dff7a', R: '#ffe14d', S: '#4da6ff', L: '#ff5d7a' };
    const c = colors[p.kind];
    const bob = Math.sin(p.t * 5) * 2;
    ctx.save();
    ctx.translate(p.x, p.y + bob);
    ctx.shadowColor = c;
    ctx.shadowBlur = 16;
    ctx.fillStyle = c;
    ctx.beginPath();
    ctx.arc(0, 0, p.r, 0, Math.PI * 2);
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.fillStyle = '#050510';
    ctx.font = 'bold 14px Arial';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(p.kind === 'L' ? '♥' : p.kind, 0, 1);
    ctx.restore();
  }

  // Enemies
  for (const e of enemies) {
    ctx.save();
    ctx.translate(e.x, e.y);
    ctx.shadowColor = e.color;
    ctx.shadowBlur = 18;
    ctx.fillStyle = e.flash > 0 ? '#ffffff' : e.color;
    ctx.strokeStyle = e.color;
    ctx.lineWidth = 2;

    if (e.type === 'drifter') {
      ctx.rotate(Math.PI / 4 + e.t);
      ctx.fillRect(-e.r * 0.7, -e.r * 0.7, e.r * 1.4, e.r * 1.4);
    } else if (e.type === 'weaver') {
      ctx.rotate(e.t * 3);
      ctx.beginPath();
      for (let k = 0; k < 3; k++) {
        const a = (k / 3) * Math.PI * 2;
        const px = Math.cos(a) * e.r, py = Math.sin(a) * e.r;
        if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fill();
    } else if (e.type === 'chaser') {
      const a = Math.atan2(player.y - e.y, player.x - e.x);
      ctx.rotate(a);
      ctx.beginPath();
      ctx.moveTo(e.r, 0);
      ctx.lineTo(-e.r * 0.8, e.r * 0.7);
      ctx.lineTo(-e.r * 0.4, 0);
      ctx.lineTo(-e.r * 0.8, -e.r * 0.7);
      ctx.closePath();
      ctx.fill();
    } else { // tank — hexagon with HP ring
      ctx.rotate(e.t * 0.6);
      ctx.beginPath();
      for (let k = 0; k < 6; k++) {
        const a = (k / 6) * Math.PI * 2;
        const px = Math.cos(a) * e.r, py = Math.sin(a) * e.r;
        if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
      }
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.strokeStyle = 'rgba(255,255,255,0.85)';
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(0, 0, e.r + 7, -Math.PI / 2, -Math.PI / 2 + (e.hp / e.maxHp) * Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();
  }

  // Player bullets
  ctx.save();
  ctx.shadowColor = '#00f0ff';
  ctx.shadowBlur = 12;
  ctx.fillStyle = '#aef7ff';
  for (const b of bullets) {
    ctx.beginPath();
    ctx.ellipse(b.x, b.y, b.r * 0.7, b.r * 1.8, 0, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();

  // Enemy bullets
  ctx.save();
  ctx.shadowColor = '#ff5d7a';
  ctx.shadowBlur = 12;
  ctx.fillStyle = '#ff8fa3';
  for (const b of enemyBullets) {
    ctx.beginPath();
    ctx.arc(b.x, b.y, b.r, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();

  // Player
  if (state === STATE.PLAYING || state === STATE.PAUSED) {
    const blink = player.invulnTimer > 0 && Math.floor(elapsed * 14) % 2 === 0;
    if (!blink) {
      ctx.save();
      ctx.translate(player.x, player.y);
      ctx.rotate(player.tilt);
      ctx.shadowColor = '#00f0ff';
      ctx.shadowBlur = 22;
      ctx.fillStyle = '#00f0ff';
      ctx.beginPath();
      ctx.moveTo(0, -player.r - 4);
      ctx.lineTo(player.r * 0.85, player.r);
      ctx.lineTo(0, player.r * 0.45);
      ctx.lineTo(-player.r * 0.85, player.r);
      ctx.closePath();
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.fillStyle = '#e8fbff';
      ctx.beginPath();
      ctx.moveTo(0, -player.r + 2);
      ctx.lineTo(player.r * 0.35, player.r * 0.4);
      ctx.lineTo(-player.r * 0.35, player.r * 0.4);
      ctx.closePath();
      ctx.fill();
      ctx.restore();

      // Shield ring
      if (player.shield) {
        ctx.save();
        ctx.translate(player.x, player.y);
        ctx.strokeStyle = 'rgba(77,166,255,0.9)';
        ctx.shadowColor = '#4da6ff';
        ctx.shadowBlur = 14;
        ctx.lineWidth = 2.5;
        ctx.beginPath();
        ctx.arc(0, 0, player.r + 10 + Math.sin(elapsed * 4) * 2, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
      }
    }
  }

  // Particles
  for (const p of particles) {
    ctx.globalAlpha = Math.max(0, p.life / p.maxLife);
    ctx.fillStyle = p.color;
    ctx.fillRect(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size);
  }
  ctx.globalAlpha = 1;

  ctx.restore();

  // Wave banner
  if (bannerTimer > 0 && state === STATE.PLAYING) {
    ctx.save();
    ctx.globalAlpha = Math.min(1, bannerTimer);
    ctx.font = '900 ' + Math.min(64, W * 0.09) + 'px Arial';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.shadowColor = '#ff4dd8';
    ctx.shadowBlur = 26;
    ctx.fillStyle = '#ff4dd8';
    ctx.fillText(bannerText, W / 2, H * 0.35);
    ctx.restore();
  }
}

// ---------- Main loop ----------
let lastTime = performance.now();
function frame(now) {
  const dt = Math.min(0.033, (now - lastTime) / 1000);
  lastTime = now;
  update(dt);
  draw();
  requestAnimationFrame(frame);
}

// Title screen high score
titleHiscoreEl.textContent = hiScore > 0 ? 'HIGH SCORE  ' + hiScore.toLocaleString() : '';
updateHUD();
requestAnimationFrame(frame);
