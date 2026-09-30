/* Asher landing — the planet.
   A low-orbit Earth rendered with three.js: day side, city lights on the night side, a cloud layer,
   a Fresnel atmosphere in the brand's atmosphere blue, the wordmark's orbit ring with its spacecraft,
   and, once you scroll to the transport chapters, a mesh of relayed packets hopping between phones
   on the night side. Scroll drives where the planet sits; nothing else needs the visitor.

   Degrades: no WebGL or no module support leaves the poster image in place. Reduced motion renders
   a still frame and only re-renders on scroll and resize. */

import * as THREE from './vendor/three/three.module.min.js';

const stage = document.getElementById('stage');
const canvas = document.getElementById('stage-canvas');
if (!stage || !canvas) throw new Error('stage missing');

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
const coarse = window.matchMedia('(pointer: coarse)').matches;
const isSmall = () => window.innerWidth < 720;

/* ── Renderer ── */
let renderer;
try {
  renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
} catch (e) {
  throw new Error('no webgl');
}
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, isSmall() ? 2 : 1.75));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(36, 1, 0.1, 120);
camera.position.set(0, 0, 6);

/* ── Palette (tokens.json) ── */
const ATMOSPHERE = new THREE.Color('#3D8FCF');
const RIM = new THREE.Color('#CFE6F7');
const WARM = new THREE.Color('#FFD9A8');

/* ── Sun ── */
const sunDir = new THREE.Vector3(2.0, 1.0, 2.0).normalize();
const sun = new THREE.DirectionalLight(0xfff3e2, 2.4);
sun.position.copy(sunDir).multiplyScalar(10);
scene.add(sun);
scene.add(new THREE.AmbientLight(0x9bb0c3, 0.11));

/* ── Textures (arrive progressively; the poster covers the wait) ── */
const loader = new THREE.TextureLoader();
const maxAniso = renderer.capabilities.getMaxAnisotropy();
function tex(url, srgb, onLoad) {
  const t = loader.load(url, (loaded) => {
    loaded.anisotropy = Math.min(8, maxAniso);
    loaded.needsUpdate = true;
    if (onLoad) onLoad(loaded);
  });
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/* A soft round sprite for every point (three.js Points are squares by default). */
const dot = (() => {
  const c = document.createElement('canvas'); c.width = c.height = 64;
  const g = c.getContext('2d'); const r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  r.addColorStop(0, 'rgba(255,255,255,1)'); r.addColorStop(0.35, 'rgba(255,255,255,.85)'); r.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = r; g.fillRect(0, 0, 64, 64);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
})();

/* ── Planet ── */
const planet = new THREE.Group();
scene.add(planet);
const segs = isSmall() ? 72 : 128;
const sphere = new THREE.SphereGeometry(1, segs, segs);

const surfaceMat = new THREE.MeshPhongMaterial({
  color: 0xffffff,
  shininess: 16,
  specular: new THREE.Color(0x2b4358),
});
const surface = new THREE.Mesh(sphere, surfaceMat);
planet.add(surface);

surfaceMat.map = tex('assets/earth/day.webp', true, () => { surfaceMat.needsUpdate = true; goLive(); });
surfaceMat.specularMap = tex('assets/earth/specular.webp', false, () => { surfaceMat.needsUpdate = true; });
surfaceMat.normalMap = tex('assets/earth/normal.webp', false, () => { surfaceMat.needsUpdate = true; });
surfaceMat.normalScale = new THREE.Vector2(0.55, 0.55);

/* City lights, only where the sun is not. */
const lightsMat = new THREE.ShaderMaterial({
  uniforms: {
    lights: { value: tex('assets/earth/lights.webp', true) },
    sunDir: { value: sunDir },
    tint: { value: WARM },
  },
  vertexShader: `
    varying vec2 vUv; varying vec3 vN;
    void main(){ vUv = uv; vN = normalize(mat3(modelMatrix) * normal);
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
  fragmentShader: `
    uniform sampler2D lights; uniform vec3 sunDir; uniform vec3 tint;
    varying vec2 vUv; varying vec3 vN;
    void main(){
      float l = dot(normalize(vN), sunDir);
      float night = smoothstep(0.18, -0.2, l);
      vec3 c = texture2D(lights, vUv).rgb;
      float lum = dot(c, vec3(0.3, 0.59, 0.11));
      gl_FragColor = vec4(tint * pow(lum, 1.1) * 2.3 * night, 1.0);
    }`,
  blending: THREE.AdditiveBlending, transparent: true, depthWrite: false,
});
planet.add(new THREE.Mesh(sphere, lightsMat));

/* Clouds */
const cloudsMat = new THREE.MeshLambertMaterial({ transparent: true, opacity: 0.55, depthWrite: false });
cloudsMat.map = tex('assets/earth/clouds.webp', true, () => { cloudsMat.needsUpdate = true; });
const clouds = new THREE.Mesh(new THREE.SphereGeometry(1.011, segs, segs), cloudsMat);
planet.add(clouds);

/* Rim light on the lit limb (the design system's "rim-lit" edge). */
const rimMat = new THREE.ShaderMaterial({
  uniforms: { sunDir: { value: sunDir }, rim: { value: RIM } },
  vertexShader: `
    varying vec3 vN; varying vec3 vV;
    void main(){ vec4 mv = modelViewMatrix * vec4(position,1.0); vV = normalize(-mv.xyz);
      vN = normalize(normalMatrix * normal); gl_Position = projectionMatrix * mv; }`,
  fragmentShader: `
    uniform vec3 sunDir; uniform vec3 rim; varying vec3 vN; varying vec3 vV;
    void main(){
      float f = pow(1.0 - max(dot(vN, vV), 0.0), 4.2);
      gl_FragColor = vec4(rim * f * 0.9, 1.0);
    }`,
  blending: THREE.AdditiveBlending, transparent: true, depthWrite: false,
});
planet.add(new THREE.Mesh(new THREE.SphereGeometry(1.004, segs, segs), rimMat));

/* Atmosphere: back-face Fresnel shell, brighter toward the sun. */
const atmoMat = new THREE.ShaderMaterial({
  uniforms: { sunDir: { value: sunDir }, cBlue: { value: ATMOSPHERE }, cRim: { value: RIM } },
  vertexShader: `
    varying vec3 vN; varying vec3 vW;
    void main(){ vN = normalize(normalMatrix * normal); vW = normalize(mat3(modelMatrix) * normal);
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }`,
  fragmentShader: `
    uniform vec3 sunDir; uniform vec3 cBlue; uniform vec3 cRim;
    varying vec3 vN; varying vec3 vW;
    void main(){
      float f = pow(0.66 + dot(vN, vec3(0.0, 0.0, 1.0)), 3.4);
      float lit = clamp(dot(vW, sunDir) * 0.9 + 0.55, 0.12, 1.0);
      vec3 c = mix(cBlue, cRim, pow(f, 2.0) * 0.6);
      gl_FragColor = vec4(c * f * lit * 1.15, 1.0);
    }`,
  side: THREE.BackSide, blending: THREE.AdditiveBlending, transparent: true, depthWrite: false,
});
planet.add(new THREE.Mesh(new THREE.SphereGeometry(1.19, 96, 96), atmoMat));

/* ── Orbit ring and spacecraft (the wordmark, in space) ── */
const orbit = new THREE.Group();
orbit.rotation.set(1.22, 0, -0.42);
planet.add(orbit);
{
  const pts = new THREE.EllipseCurve(0, 0, 1.46, 1.46, 0, Math.PI * 2, false, 0).getPoints(180);
  const g = new THREE.BufferGeometry().setFromPoints(pts.map(p => new THREE.Vector3(p.x, p.y, 0)));
  orbit.add(new THREE.LineLoop(g, new THREE.LineBasicMaterial({ color: RIM, transparent: true, opacity: 0.22 })));
}
const craft = new THREE.Mesh(new THREE.SphereGeometry(0.02, 12, 12), new THREE.MeshBasicMaterial({ color: RIM }));
orbit.add(craft);
const craftGlow = new THREE.Points(
  new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0], 3)),
  new THREE.PointsMaterial({ map: dot, color: RIM, size: 0.16, transparent: true, opacity: 0.35, sizeAttenuation: true, depthWrite: false, blending: THREE.AdditiveBlending })
);
craft.add(craftGlow);

/* ── Mesh: phones on the night side relaying for each other ──
   Placed in the planet's frame near the terminator, facing the camera, so the arcs stay in view
   while the surface turns slowly beneath them. */
const mesh = new THREE.Group();
planet.add(mesh);
const facing = new THREE.Vector3(-0.58, 0.12, 0.80).normalize();
const seed = (i) => { const x = Math.sin(i * 12.9898) * 43758.5453; return x - Math.floor(x); };
function nodeAt(i) {
  const basis = new THREE.Matrix4().lookAt(facing, new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 1, 0));
  const spread = 0.42;
  const a = (seed(i) - 0.5) * 2 * spread, b = (seed(i + 40) - 0.5) * 2 * spread * 0.8;
  const v = new THREE.Vector3(a, b, 1).normalize().applyMatrix4(basis);
  return v.multiplyScalar(1.012);
}
const nodePositions = Array.from({ length: 14 }, (_, i) => nodeAt(i));
const LINKS = [[0, 1], [1, 2], [2, 3], [0, 4], [4, 5], [5, 6], [3, 7], [7, 8], [6, 9], [9, 10], [10, 11], [2, 12], [12, 13], [8, 13], [1, 5], [4, 9]];
mesh.add(new THREE.Points(
  new THREE.BufferGeometry().setFromPoints(nodePositions),
  new THREE.PointsMaterial({ map: dot, color: ATMOSPHERE, size: 0.04, sizeAttenuation: true, transparent: true, opacity: 0.95, depthWrite: false })
));
const arcMat = new THREE.LineBasicMaterial({ color: ATMOSPHERE, transparent: true, opacity: 0.42 });
const curves = LINKS.map(([a, b]) => {
  const A = nodePositions[a], B = nodePositions[b];
  const mid = A.clone().add(B).multiplyScalar(0.5);
  const lift = 1 + A.distanceTo(B) * 0.5;
  mid.normalize().multiplyScalar(lift);
  const c = new THREE.QuadraticBezierCurve3(A, mid, B);
  mesh.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(c.getPoints(40)), arcMat));
  return c;
});
const packetPos = new Float32Array(curves.length * 3);
const packets = new THREE.Points(
  new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(packetPos, 3)),
  new THREE.PointsMaterial({ map: dot, color: RIM, size: 0.075, sizeAttenuation: true, transparent: true, opacity: 0.95, depthWrite: false, blending: THREE.AdditiveBlending })
);
mesh.add(packets);
const packetPhase = curves.map((_, i) => (i * 0.37) % 1);
const packetSpeed = curves.map((_, i) => 0.10 + ((i * 7) % 5) * 0.025);

/* ── Stars ── */
{
  const n = isSmall() ? 900 : 1800;
  const pos = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    const v = new THREE.Vector3().randomDirection().multiplyScalar(60 + Math.random() * 20);
    pos.set([v.x, v.y, v.z], i * 3);
  }
  const stars = new THREE.Points(
    new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(pos, 3)),
    new THREE.PointsMaterial({ map: dot, color: RIM, size: 0.14, sizeAttenuation: true, transparent: true, opacity: 0.7, depthWrite: false })
  );
  stars.name = 'stars';
  scene.add(stars);
}

/* ── Scroll choreography ── */
const sections = ['#how', '#security', '#offline', '#organisations'].map(s => document.querySelector(s));
const state = { x: 0, y: 0, s: 1, meshA: 0, yaw: 0, fade: 1, dim: 1 };
const target = { ...state };
const pointer = { x: 0, y: 0, tx: 0, ty: 0 };

function halfWidth() { return Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * camera.position.z * camera.aspect; }

/* Fractional chapter index: 0 while the hero fills the viewport; each chapter adds 1 as its top
   travels from the bottom of the viewport to a fifth of the way down it. */
function progress() {
  const vh = window.innerHeight;
  let f = 0;
  for (const el of sections) {
    if (!el) continue;
    const top = el.getBoundingClientRect().top;
    const t = (vh - top) / (vh * 0.8);
    if (t <= 0) break;
    f += Math.min(1, t);
  }
  return f;
}

function keyframe(i, hw, small) {
  /* Where the planet sits for chapter i, in world units at z = 0. */
  if (small) {
    switch (i) {
      case 0: return { x: 0.2, y: -2.15, s: 1.8, meshA: 0, dim: 1 };
      case 1: return { x: 0.55, y: 2.35, s: 0.75, meshA: 1, dim: 0.85 };
      case 2: return { x: -0.7, y: -2.7, s: 0.8, meshA: 0.3, dim: 0.5 };
      case 3: return { x: 0.6, y: 2.5, s: 0.85, meshA: 1, dim: 0.8 };
      default: return { x: 0.6, y: 4.2, s: 0.6, meshA: 0, dim: 0 };
    }
  }
  switch (i) {
    case 0: return { x: Math.max(0.9, hw - 1.05), y: -0.95, s: 2.15, meshA: 0, dim: 1 };
    case 1: return { x: hw - 1.0, y: 0.95, s: 0.8, meshA: 1, dim: 0.85 };
    case 2: return { x: -(hw - 0.85), y: -0.65, s: 0.55, meshA: 0.3, dim: 0.5 };
    case 3: return { x: hw - 1.3, y: -0.05, s: 0.95, meshA: 1, dim: 0.8 };
    default: return { x: hw + 1.3, y: 0.6, s: 0.6, meshA: 0, dim: 0 };
  }
}

const smooth = (a, b, t) => a + (b - a) * t;
const ease = (t) => t * t * (3 - 2 * t);

function retarget() {
  const f = progress();
  const i = Math.floor(f), t = ease(Math.min(1, f - i));
  const hw = halfWidth(), small = isSmall();
  const A = keyframe(i, hw, small), B = keyframe(i + 1, hw, small);
  target.x = smooth(A.x, B.x, t);
  target.y = smooth(A.y, B.y, t);
  target.s = smooth(A.s, B.s, t);
  target.meshA = smooth(A.meshA, B.meshA, t);
  target.dim = smooth(A.dim, B.dim, t);
  target.yaw = f * 0.9;
  target.fade = f < 3 ? 1 : Math.max(0, 1 - (f - 3) * 1.4);
}

let arcBoost = 0;
document.addEventListener('asher:scene', (e) => { if (e.detail && e.detail.state === 'mesh') arcBoost = 1; });

/* ── Frame ── */
const clock = new THREE.Clock();
let live = false, raf = 0, spin = 0;
const stars = scene.getObjectByName('stars');

function render(dt, instant) {
  const k = instant ? 1 : 1 - Math.pow(0.001, dt); // frame-rate independent lerp (~6%/frame at 60 Hz)
  for (const key of ['x', 'y', 's', 'meshA', 'yaw', 'fade', 'dim']) state[key] = smooth(state[key], target[key], k);

  if (!reduceMotion.matches) spin += dt * 0.022;
  planet.position.set(state.x, state.y, 0);
  planet.scale.setScalar(state.s);
  surface.rotation.y = spin + state.yaw;
  clouds.rotation.y = spin * 1.18 + state.yaw;
  planet.rotation.z = -0.32;
  planet.rotation.x = 0.18;

  const T = clock.elapsedTime;
  craft.position.set(Math.cos(T * 0.21) * 1.46, Math.sin(T * 0.21) * 1.46, 0);
  if (stars) stars.rotation.y = T * 0.004;

  const a = state.meshA * (0.75 + 0.25 * arcBoost);
  arcMat.opacity = 0.42 * a;
  packets.material.opacity = 0.95 * a;
  mesh.visible = a > 0.01;
  if (mesh.visible && !reduceMotion.matches) {
    for (let i = 0; i < curves.length; i++) {
      const u = (T * packetSpeed[i] * (1 + arcBoost * 0.8) + packetPhase[i]) % 1;
      const p = curves[i].getPoint(u);
      packetPos[i * 3] = p.x; packetPos[i * 3 + 1] = p.y; packetPos[i * 3 + 2] = p.z;
    }
    packets.geometry.attributes.position.needsUpdate = true;
  }
  arcBoost = Math.max(0, arcBoost - dt * 0.35);

  sun.intensity = 2.4 * state.dim;
  cloudsMat.opacity = 0.55 * state.dim;

  pointer.x = smooth(pointer.x, pointer.tx, k * 0.6);
  pointer.y = smooth(pointer.y, pointer.ty, k * 0.6);
  camera.position.x = pointer.x * 0.12;
  camera.position.y = pointer.y * 0.08;
  camera.lookAt(0, 0, 0);

  if (live) canvas.style.opacity = String(state.fade);
  renderer.render(scene, camera);
}

function frame() {
  raf = 0;
  const dt = Math.min(0.05, clock.getDelta());
  render(dt, false);
  if (!document.hidden && !reduceMotion.matches && (state.fade > 0.005 || target.fade > 0)) raf = requestAnimationFrame(frame);
}
function wake() { if (!raf && !reduceMotion.matches) { clock.getDelta(); raf = requestAnimationFrame(frame); } }

function resize() {
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  retarget();
  if (reduceMotion.matches) render(0, true); else wake();
}

function goLive() {
  if (live) return;
  live = true;
  retarget();
  Object.assign(state, target);
  render(0, true);
  stage.classList.add('is-live');
  /* The CSS handles the first fade-in; after it, scroll owns the opacity. */
  setTimeout(() => { canvas.style.transition = 'none'; }, 1400);
  if (reduceMotion.matches) render(0, true); else wake();
}

/* Poster mode until textures arrive: draw nothing (canvas stays transparent). */
let scrollTick = false;
window.addEventListener('scroll', () => {
  if (scrollTick) return;
  scrollTick = true;
  requestAnimationFrame(() => {
    scrollTick = false;
    retarget();
    if (reduceMotion.matches) render(0, true); else wake();
  });
}, { passive: true });
window.addEventListener('resize', resize);
document.addEventListener('visibilitychange', () => { if (!document.hidden) wake(); });
if (!coarse) {
  window.addEventListener('pointermove', (e) => {
    pointer.tx = (e.clientX / window.innerWidth) * 2 - 1;
    pointer.ty = -((e.clientY / window.innerHeight) * 2 - 1);
    wake();
  }, { passive: true });
}
reduceMotion.addEventListener('change', () => { retarget(); render(0, true); wake(); });

resize();
