import React, { useEffect, useMemo, useRef, useState } from 'react';
import { styles } from '../../styles/dashboardStyles';
import useLanguage from '../../hooks/useLanguage';
import PersonGraph from './PersonGraph';

const GW = 1000;
const GH = 620;
// Free-form canvas: nodes may use the whole area, clamped only to stay visible.
const PAD = 70;

function nameById(people, id) {
  const p = (people || []).find(x => x.id === id);
  return p ? p.name : id;
}

function circleStart(index, total, rotation = 0) {
  const cx = GW / 2;
  const cy = GH / 2;
  if (total <= 1) return { x: cx, y: cy };
  const r = Math.min(220, 80 + total * 16);
  const angle = (2 * Math.PI * index) / total - Math.PI / 2 + rotation;
  // Deterministic jitter so overlapping seeds separate immediately.
  const jx = ((index * 37) % 41) - 20;
  const jy = ((index * 53) % 41) - 20;
  return { x: cx + r * Math.cos(angle) + jx, y: cy + r * Math.sin(angle) + jy };
}

/**
 * Tiny force-directed layout (Fruchterman–Reingold style), zero dependencies.
 * Repulsion spreads everyone out, springs pull connected nodes together,
 * edge-midpoint forces keep labels from stacking and crossing edges apart,
 * weak gravity keeps the whole graph centered. n is small (<50) so 250
 * iterations is instant.
 */
function segmentsCross(p1, p2, p3, p4) {
  const d = (a, b, c) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
  const d1 = d(p3, p4, p1);
  const d2 = d(p3, p4, p2);
  const d3 = d(p1, p2, p3);
  const d4 = d(p1, p2, p4);
  return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
}

// BFS order from the highest-degree node so neighbours start adjacent on the
// initial ring — far fewer crossings for the simulation to untangle.
function bfsOrder(nodes, edges) {
  const adj = {};
  nodes.forEach(nd => { adj[nd.id] = new Set(); });
  edges.forEach(e => {
    if (!adj[e.from] || !adj[e.to]) return;
    adj[e.from].add(e.to);
    adj[e.to].add(e.from);
  });
  const deg = id => (adj[id] ? adj[id].size : 0);
  const visited = new Set();
  const order = [];
  const starts = [...nodes].sort((a, b) => deg(b.id) - deg(a.id));
  starts.forEach(s => {
    if (visited.has(s.id)) return;
    const q = [s.id];
    visited.add(s.id);
    while (q.length) {
      const cur = q.shift();
      order.push(cur);
      const nbs = [...(adj[cur] || [])].sort((x, y) => deg(y) - deg(x));
      nbs.forEach(nb => {
        if (!visited.has(nb)) { visited.add(nb); q.push(nb); }
      });
    }
  });
  return order;
}

function forceLayout(nodes, edges, seed, fromScratch = false, rotation = 0) {
  const n = nodes.length;
  if (n === 0) return {};
  if (n === 1) return { [nodes[0].id]: { x: GW / 2, y: GH / 2 } };
  const area = GW * GH;
  const k = Math.max(90, Math.min(210, Math.sqrt(area / n) * 0.55));

  const orderRank = {};
  bfsOrder(nodes, edges).forEach((id, i) => { orderRank[id] = i; });

  const pos = {};
  nodes.forEach((nd, i) => {
    if (!fromScratch && seed && seed[nd.id]) {
      pos[nd.id] = { ...seed[nd.id] };
    } else {
      pos[nd.id] = circleStart(orderRank[nd.id] ?? i, n, rotation);
    }
  });

  let temp = 70;
  const cooling = 0.96;
  const iters = 350;
  for (let it = 0; it < iters; it++) {
    const disp = {};
    nodes.forEach(nd => { disp[nd.id] = { x: 0, y: 0 }; });
    // Repulsion between every pair.
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = nodes[i].id;
        const b = nodes[j].id;
        let dx = pos[a].x - pos[b].x;
        let dy = pos[a].y - pos[b].y;
        let d = Math.hypot(dx, dy) || 0.01;
        const f = (k * k) / d;
        dx /= d;
        dy /= d;
        disp[a].x += dx * f;
        disp[a].y += dy * f;
        disp[b].x -= dx * f;
        disp[b].y -= dy * f;
      }
    }
    // Springs along edges.
    edges.forEach(e => {
      if (!pos[e.from] || !pos[e.to]) return;
      let dx = pos[e.from].x - pos[e.to].x;
      let dy = pos[e.from].y - pos[e.to].y;
      let d = Math.hypot(dx, dy) || 0.01;
      const f = (d * d) / k;
      dx /= d;
      dy /= d;
      disp[e.from].x -= dx * f;
      disp[e.from].y -= dy * f;
      disp[e.to].x += dx * f;
      disp[e.to].y += dy * f;
    });
    // Minimum edge length from the rendered label pill: a long relationship
    // word stretches its edge so the pill never gets squeezed between nodes.
    // (Mirrors the canvas truncation: max 8 chars + ellipsis.)
    edges.forEach(e => {
      if (!pos[e.from] || !pos[e.to]) return;
      const labelLen = Math.min((e.relationship || '').length, 9);
      const pillW = Math.max(34, labelLen * 13 + 16);
      const minEdge = Math.min(300, pillW + 2 * 29 + 36);
      let dx = pos[e.to].x - pos[e.from].x;
      let dy = pos[e.to].y - pos[e.from].y;
      const d = Math.hypot(dx, dy) || 0.01;
      if (d < minEdge) {
        const push = ((minEdge - d) / d) * k * 0.25;
        dx /= d;
        dy /= d;
        disp[e.from].x -= dx * push;
        disp[e.from].y -= dy * push;
        disp[e.to].x += dx * push;
        disp[e.to].y += dy * push;
      }
    });
    // Node-on-edge repulsion: keep nodes that are NOT incident to an edge
    // away from that edge's segment (this is what pushes a bystander like
    // Jun off the kuanglang—ahwei line). O(n*e), trivial for small graphs.
    edges.forEach(e => {
      if (!pos[e.from] || !pos[e.to]) return;
      const ax = pos[e.from].x;
      const ay = pos[e.from].y;
      const bx = pos[e.to].x;
      const by = pos[e.to].y;
      const abx = bx - ax;
      const aby = by - ay;
      const len2 = abx * abx + aby * aby || 0.01;
      nodes.forEach(nd => {
        const id = nd.id;
        if (id === e.from || id === e.to) return;
        const px = pos[id].x;
        const py = pos[id].y;
        let tt = ((px - ax) * abx + (py - ay) * aby) / len2;
        tt = Math.max(0, Math.min(1, tt));
        const cx = ax + tt * abx;
        const cy = ay + tt * aby;
        let dx = px - cx;
        let dy = py - cy;
        const d = Math.hypot(dx, dy);
        const minD = 80;
        if (d < minD) {
          let nx = dx;
          let ny = dy;
          let ndist = d;
          if (ndist < 0.01) { nx = -aby; ny = abx; ndist = Math.hypot(nx, ny) || 0.01; }
          const f = ((minD - ndist) / minD) * k * 1.2;
          disp[id].x += (nx / ndist) * f;
          disp[id].y += (ny / ndist) * f;
        }
      });
    });
    // Edge-label / crossing separation: push apart edges whose midpoints are
    // close, and push much harder when the segments actually cross (shared
    // endpoints excluded). The push is distributed to the four endpoints.
    const validEdges = edges.filter(e => pos[e.from] && pos[e.to]);
    const mids = validEdges.map(e => ({
      e,
      x: (pos[e.from].x + pos[e.to].x) / 2,
      y: (pos[e.from].y + pos[e.to].y) / 2
    }));
    for (let i = 0; i < mids.length; i++) {
      for (let j = i + 1; j < mids.length; j++) {
        const A = mids[i];
        const B = mids[j];
        if (A.e.from === B.e.from || A.e.from === B.e.to
          || A.e.to === B.e.from || A.e.to === B.e.to) {
          continue;
        }
        let dx = A.x - B.x;
        let dy = A.y - B.y;
        const d = Math.hypot(dx, dy);
        const crossing = segmentsCross(
          pos[A.e.from], pos[A.e.to], pos[B.e.from], pos[B.e.to]);
        const minD = crossing ? 230 : 135;
        if (d < minD) {
          let nx = dx;
          let ny = dy;
          let ndist = d;
          if (ndist < 0.01) { nx = i - j || 1; ny = 1; ndist = Math.hypot(nx, ny); }
          const f = ((minD - ndist) / minD) * k * (crossing ? 2.2 : 1.0);
          nx /= ndist;
          ny /= ndist;
          disp[A.e.from].x += nx * f * 0.5;
          disp[A.e.from].y += ny * f * 0.5;
          disp[A.e.to].x += nx * f * 0.5;
          disp[A.e.to].y += ny * f * 0.5;
          disp[B.e.from].x -= nx * f * 0.5;
          disp[B.e.from].y -= ny * f * 0.5;
          disp[B.e.to].x -= nx * f * 0.5;
          disp[B.e.to].y -= ny * f * 0.5;
        }
      }
    }
    // Weak gravity toward canvas center (keeps isolates from drifting off).
    // Deliberately faint so disconnected pairs can spread freely.
    nodes.forEach(nd => {
      const id = nd.id;
      disp[id].x += (GW / 2 - pos[id].x) * 0.008;
      disp[id].y += (GH / 2 - pos[id].y) * 0.008;
    });
    // Apply, capped by temperature.
    nodes.forEach(nd => {
      const id = nd.id;
      const dx = disp[id].x;
      const dy = disp[id].y;
      const d = Math.hypot(dx, dy) || 0.01;
      const capped = Math.min(d, temp);
      pos[id].x += (dx / d) * capped;
      pos[id].y += (dy / d) * capped;
      pos[id].x = Math.max(PAD, Math.min(GW - PAD, pos[id].x));
      pos[id].y = Math.max(PAD, Math.min(GH - PAD, pos[id].y));
    });
    temp *= cooling;
  }
  // Final de-overlap passes: node-node, node-edge, edge-length, edge-label.
  for (let pass = 0; pass < 40; pass++) {
    let moved = false;
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = nodes[i].id;
        const b = nodes[j].id;
        let dx = pos[a].x - pos[b].x;
        let dy = pos[a].y - pos[b].y;
        let d = Math.hypot(dx, dy);
        const minD = 110;
        if (d < minD) {
          if (d < 0.01) { dx = (i - j) || 1; dy = 1; d = Math.hypot(dx, dy); }
          const push = ((minD - d) / d) * 0.5;
          pos[a].x += dx * push;
          pos[a].y += dy * push;
          pos[b].x -= dx * push;
          pos[b].y -= dy * push;
          moved = true;
        }
      }
    }
    // Push bystander nodes off edge segments.
    edges.forEach(e => {
      if (!pos[e.from] || !pos[e.to]) return;
      const ax = pos[e.from].x;
      const ay = pos[e.from].y;
      const bx = pos[e.to].x;
      const by = pos[e.to].y;
      const abx = bx - ax;
      const aby = by - ay;
      const len2 = abx * abx + aby * aby || 0.01;
      nodes.forEach(nd => {
        const id = nd.id;
        if (id === e.from || id === e.to) return;
        let tt = ((pos[id].x - ax) * abx + (pos[id].y - ay) * aby) / len2;
        tt = Math.max(0, Math.min(1, tt));
        const cx = ax + tt * abx;
        const cy = ay + tt * aby;
        let dx = pos[id].x - cx;
        let dy = pos[id].y - cy;
        let d = Math.hypot(dx, dy);
        const minD = 70;
        if (d < minD) {
          if (d < 0.01) { dx = -aby; dy = abx; d = Math.hypot(dx, dy) || 0.01; }
          const push = ((minD - d) / d) * 0.6;
          pos[id].x += dx * push;
          pos[id].y += dy * push;
          moved = true;
        }
      });
    });
    // Stretch short edges so long labels fit (same pill math as above).
    edges.forEach(e => {
      if (!pos[e.from] || !pos[e.to]) return;
      const labelLen = Math.min((e.relationship || '').length, 9);
      const pillW = Math.max(34, labelLen * 13 + 16);
      const minEdge = Math.min(300, pillW + 2 * 29 + 36);
      let dx = pos[e.to].x - pos[e.from].x;
      let dy = pos[e.to].y - pos[e.from].y;
      let d = Math.hypot(dx, dy) || 0.01;
      if (d < minEdge) {
        const push = ((minEdge - d) / d) * 0.3;
        dx /= d;
        dy /= d;
        pos[e.from].x -= dx * push * d * 0.5;
        pos[e.from].y -= dy * push * d * 0.5;
        pos[e.to].x += dx * push * d * 0.5;
        pos[e.to].y += dy * push * d * 0.5;
        moved = true;
      }
    });
    // Separate crossing / overlapping edge labels in the final pass as well.
    {
      const valid = edges.filter(e => pos[e.from] && pos[e.to]);
      for (let i = 0; i < valid.length; i++) {
        for (let j = i + 1; j < valid.length; j++) {
          const A = valid[i];
          const B = valid[j];
          if (A.from === B.from || A.from === B.to || A.to === B.from || A.to === B.to) {
            continue;
          }
          const mxA = (pos[A.from].x + pos[A.to].x) / 2;
          const myA = (pos[A.from].y + pos[A.to].y) / 2;
          const mxB = (pos[B.from].x + pos[B.to].x) / 2;
          const myB = (pos[B.from].y + pos[B.to].y) / 2;
          let dx = mxA - mxB;
          let dy = myA - myB;
          let d = Math.hypot(dx, dy);
          const crossing = segmentsCross(pos[A.from], pos[A.to], pos[B.from], pos[B.to]);
          const minD = crossing ? 200 : 125;
          if (d < minD) {
            if (d < 0.01) { dx = i - j || 1; dy = 1; d = Math.hypot(dx, dy); }
            const push = ((minD - d) / d) * 0.35;
            dx /= d;
            dy /= d;
            pos[A.from].x += dx * push * d * 0.25;
            pos[A.from].y += dy * push * d * 0.25;
            pos[A.to].x += dx * push * d * 0.25;
            pos[A.to].y += dy * push * d * 0.25;
            pos[B.from].x -= dx * push * d * 0.25;
            pos[B.from].y -= dy * push * d * 0.25;
            pos[B.to].x -= dx * push * d * 0.25;
            pos[B.to].y -= dy * push * d * 0.25;
            moved = true;
          }
        }
      }
    }
    nodes.forEach(nd => {
      const p = pos[nd.id];
      p.x = Math.max(PAD, Math.min(GW - PAD, p.x));
      p.y = Math.max(PAD, Math.min(GH - PAD, p.y));
    });
    if (!moved) break;
  }
  return pos;
}

/**
 * Step 3 person-context editor, graph-first:
 * interactive node/edge canvas on top, list view kept as a toggle.
 */
export default function PersonContextPanel({ context, onChange, disabled = false }) {
  const { t } = useLanguage();
  const people = context?.people || [];
  const relationships = context?.relationships || [];

  const [view, setView] = useState('graph');
  const [positions, setPositions] = useState({});
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [selectedEdgeIndex, setSelectedEdgeIndex] = useState(null);
  const [connectSourceId, setConnectSourceId] = useState(null);
  const [newPersonName, setNewPersonName] = useState('');
  const [aliasDraft, setAliasDraft] = useState('');

  // Auto-layout: re-run the force simulation whenever the graph structure
  // (node set or edge set) changes. Seeded with current positions so manual
  // drags survive renames; newcomers settle into a sensible spot.
  const structureKey = useMemo(() => {
    const ids = [...people.map(p => p.id)].sort().join(',');
    const es = [...relationships.map(r => [r.from, r.to].sort().join('~'))].sort().join(',');
    return `${ids}|${es}`;
  }, [people, relationships]);

  useEffect(() => {
    setPositions(prev => forceLayout(people, relationships, prev));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [structureKey]);

  const applyAutoLayout = () => {
    // Rotate the fresh start each click so repeated clicks offer genuinely
    // different untangled arrangements to pick from.
    rotationRef.current += 0.9;
    setPositions(() => forceLayout(people, relationships, {}, true, rotationRef.current));
  };
  const rotationRef = useRef(0);

  // Clear selection when the underlying data disappears.
  useEffect(() => {
    if (selectedNodeId && !people.some(p => p.id === selectedNodeId)) {
      setSelectedNodeId(null);
    }
    if (connectSourceId && !people.some(p => p.id === connectSourceId)) {
      setConnectSourceId(null);
    }
    if (selectedEdgeIndex != null && selectedEdgeIndex >= relationships.length) {
      setSelectedEdgeIndex(null);
    }
  }, [people, relationships, selectedNodeId, connectSourceId, selectedEdgeIndex]);

  const positioned = useMemo(
    () => people.map(p => ({ ...p, ...(positions[p.id] || { x: GW / 2, y: GH / 2 }) })),
    [people, positions]
  );

  const selectedNode = people.find(p => p.id === selectedNodeId) || null;
  const selectedEdge = selectedEdgeIndex != null ? relationships[selectedEdgeIndex] : null;

  const update = (next) => {
    if (disabled) return;
    onChange(next);
  };

  const handleMoveNode = (id, pt) => {
    setPositions(prev => ({
      ...prev,
      [id]: { x: Math.max(40, Math.min(GW - 40, pt.x)), y: Math.max(40, Math.min(GH - 40, pt.y)) }
    }));
  };

  const handleSelectNode = (id) => {
    if (connectSourceId && id !== connectSourceId) {
      // Complete a connection: source -> clicked node.
      const exists = relationships.some(r =>
        (r.from === connectSourceId && r.to === id) || (r.from === id && r.to === connectSourceId));
      if (!exists) {
        const nextRels = [...relationships, { from: connectSourceId, to: id, relationship: '认识' }];
        update({ people, relationships: nextRels });
        setSelectedEdgeIndex(nextRels.length - 1);
      }
      setConnectSourceId(null);
      setSelectedNodeId(id);
      return;
    }
    setSelectedNodeId(id);
    setSelectedEdgeIndex(null);
    setAliasDraft('');
  };

  const handleSelectEdge = (i) => {
    setSelectedEdgeIndex(i);
    setSelectedNodeId(null);
  };

  const handlePersonName = (id, name) => {
    update({ people: people.map(p => p.id === id ? { ...p, name } : p), relationships });
  };

  const handleAliasAdd = (id, aliasText) => {
    const alias = (aliasText || '').trim();
    if (!alias) return;
    update({
      people: people.map(p => {
        if (p.id !== id || p.name === alias || (p.aliases || []).includes(alias)) return p;
        return { ...p, aliases: [...(p.aliases || []), alias] };
      }),
      relationships
    });
  };

  const handleAliasRemove = (id, alias) => {
    update({
      people: people.map(p => p.id === id ? { ...p, aliases: (p.aliases || []).filter(a => a !== alias) } : p),
      relationships
    });
  };

  const handlePersonRemove = (id) => {
    update({
      people: people.filter(p => p.id !== id),
      relationships: relationships.filter(r => r.from !== id && r.to !== id)
    });
    if (selectedNodeId === id) setSelectedNodeId(null);
    if (connectSourceId === id) setConnectSourceId(null);
  };

  const handlePersonAdd = () => {
    const name = newPersonName.trim();
    if (!name) return;
    const id = `p${Date.now().toString(36)}`;
    update({ people: [...people, { id, name, aliases: [] }], relationships });
    setNewPersonName('');
    setSelectedNodeId(id);
    setSelectedEdgeIndex(null);
  };

  const handleRelEdit = (idx, relationship) => {
    update({ people, relationships: relationships.map((r, i) => i === idx ? { ...r, relationship } : r) });
  };

  const handleRelRemove = (idx) => {
    update({ people, relationships: relationships.filter((_, i) => i !== idx) });
    if (selectedEdgeIndex === idx) setSelectedEdgeIndex(null);
  };

  const inputStyle = { ...styles.input, opacity: disabled ? 0.6 : 1 };
  const smallBtn = { ...styles.buttonSecondary, padding: '4px 10px', fontSize: '0.8rem' };

  return (
    <div style={{ marginTop: '12px', border: '1px solid var(--border)', borderRadius: '6px', padding: '12px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', flexWrap: 'wrap' }}>
        <div style={{ fontWeight: 700 }}>{t('personContext.reviewTitle')}</div>
        <span style={{ flex: 1 }} />
        <button type="button" style={{ ...smallBtn, fontWeight: view === 'graph' ? 700 : 400 }} disabled={disabled} onClick={() => setView('graph')}>
          {t('personContext.viewGraph')}
        </button>
        <button type="button" style={{ ...smallBtn, fontWeight: view === 'list' ? 700 : 400 }} disabled={disabled} onClick={() => setView('list')}>
          {t('personContext.viewList')}
        </button>
      </div>

      {people.length === 0 && (
        <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '8px' }}>
          {t('personContext.empty')}
        </div>
      )}

      {view === 'graph' ? (
        <div>
          <PersonGraph
            people={positioned}
            relationships={relationships}
            selectedNodeId={selectedNodeId}
            selectedEdgeIndex={selectedEdgeIndex}
            connectSourceId={connectSourceId}
            onSelectNode={handleSelectNode}
            onSelectEdge={handleSelectEdge}
            onMoveNode={handleMoveNode}
            onAutoLayout={applyAutoLayout}
            onBackgroundClick={() => { setSelectedNodeId(null); setSelectedEdgeIndex(null); }}
            disabled={disabled}
          />

          {connectSourceId && (
            <div style={{ marginTop: '8px', fontSize: '0.85rem', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '6px', padding: '8px 10px', display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
              <span>已选起点 <strong>{nameById(people, connectSourceId)}</strong>，再点另一个节点即可连线（默认“认识”，可改）</span>
              <button type="button" style={smallBtn} disabled={disabled} onClick={() => setConnectSourceId(null)}>取消连接</button>
            </div>
          )}

          {/* selection editor */}
          {selectedNode && (
            <div style={{ marginTop: '10px', padding: '10px', background: 'var(--bg-input)', borderRadius: '6px' }}>
              <div style={{ fontWeight: 700, marginBottom: '6px', fontSize: '0.9rem' }}>节点：{selectedNode.name}</div>
              <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
                <input
                  style={{ ...inputStyle, maxWidth: '180px' }}
                  value={selectedNode.name}
                  disabled={disabled}
                  onChange={e => handlePersonName(selectedNode.id, e.target.value)}
                  placeholder={t('personContext.personName')}
                />
                <button type="button" style={smallBtn} disabled={disabled} onClick={() => { setConnectSourceId(selectedNode.id); }}>
                  从此节点连线
                </button>
                <button type="button" style={smallBtn} disabled={disabled} onClick={() => handlePersonRemove(selectedNode.id)}>
                  {t('personContext.removePerson')}
                </button>
              </div>
              <div style={{ marginTop: '8px', display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center' }}>
                {(selectedNode.aliases || []).map(a => (
                  <span key={a} style={{ fontSize: '0.8rem', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: '12px', padding: '2px 8px' }}>
                    {a}
                    <button
                      type="button"
                      disabled={disabled}
                      onClick={() => handleAliasRemove(selectedNode.id, a)}
                      style={{ marginLeft: '6px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)' }}
                      title={t('personContext.removeAlias')}
                    >
                      ×
                    </button>
                  </span>
                ))}
                <input
                  style={{ maxWidth: '140px', padding: '4px 8px', fontSize: '0.8rem', borderRadius: '4px', border: '1px solid var(--border)' }}
                  value={aliasDraft}
                  disabled={disabled}
                  onChange={e => setAliasDraft(e.target.value)}
                  placeholder={t('personContext.addAliasPh')}
                />
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => { handleAliasAdd(selectedNode.id, aliasDraft); setAliasDraft(''); }}
                  style={{ background: 'none', border: '1px solid var(--border)', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem', padding: '4px 8px' }}
                >
                  +
                </button>
              </div>
              <div style={{ marginTop: '6px', fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                与 {selectedNode.name} 有连线：{(relationships.filter(r => r.from === selectedNode.id || r.to === selectedNode.id).map(r => {
                  const other = r.from === selectedNode.id ? nameById(people, r.to) : nameById(people, r.from);
                  return `${other}（${r.relationship}）`;
                }).join('、')) || '无'}
              </div>
            </div>
          )}

          {selectedEdge && (
            <div style={{ marginTop: '10px', padding: '10px', background: 'var(--bg-input)', borderRadius: '6px', display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
              <span style={{ fontSize: '0.85rem' }}>
                {nameById(people, selectedEdge.from)} ───
              </span>
              <input
                style={{ ...inputStyle, maxWidth: '160px' }}
                value={selectedEdge.relationship}
                disabled={disabled}
                onChange={e => handleRelEdit(selectedEdgeIndex, e.target.value)}
              />
              <span style={{ fontSize: '0.85rem' }}>─── {nameById(people, selectedEdge.to)}</span>
              <button type="button" style={smallBtn} disabled={disabled} onClick={() => handleRelRemove(selectedEdgeIndex)}>
                {t('personContext.removeRelation')}
              </button>
            </div>
          )}

          {!selectedNode && selectedEdgeIndex == null && people.length > 0 && (
            <div style={{ marginTop: '8px', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              点任意节点改名/加别名/连线，点连线上的标签改关系。
            </div>
          )}

          <div style={{ display: 'flex', gap: '8px', marginTop: '10px', flexWrap: 'wrap' }}>
            <input
              style={{ ...inputStyle, maxWidth: '220px' }}
              value={newPersonName}
              disabled={disabled}
              onChange={e => setNewPersonName(e.target.value)}
              placeholder={t('personContext.newPersonPh')}
            />
            <button type="button" style={smallBtn} disabled={disabled} onClick={handlePersonAdd}>
              {t('personContext.addPerson')}
            </button>
          </div>
        </div>
      ) : (
        <ListEditor
          people={people}
          relationships={relationships}
          inputStyle={inputStyle}
          smallBtn={smallBtn}
          disabled={disabled}
          onPersonName={handlePersonName}
          onAliasAdd={handleAliasAdd}
          onAliasRemove={handleAliasRemove}
          onPersonRemove={handlePersonRemove}
          onPersonAdd={handlePersonAdd}
          newPersonName={newPersonName}
          setNewPersonName={setNewPersonName}
          onRelEdit={handleRelEdit}
          onRelRemove={handleRelRemove}
          onRelAdd={(from, to, rel) => {
            if (!from || !to || !rel.trim() || from === to) return;
            update({ people, relationships: [...relationships, { from, to, relationship: rel.trim() }] });
          }}
          t={t}
        />
      )}
    </div>
  );
}

function ListEditor({
  people, relationships, inputStyle, smallBtn, disabled,
  onPersonName, onAliasAdd, onAliasRemove, onPersonRemove,
  onPersonAdd, newPersonName, setNewPersonName,
  onRelEdit, onRelRemove, onRelAdd, t
}) {
  const [relFrom, setRelFrom] = useState('');
  const [relTo, setRelTo] = useState('');
  const [relDesc, setRelDesc] = useState('');
  return (
    <div>
      {people.map(p => (
        <div key={p.id} style={{ marginBottom: '10px', padding: '8px', background: 'var(--bg-input)', borderRadius: '6px' }}>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
            <input
              style={{ ...inputStyle, maxWidth: '180px' }}
              value={p.name}
              disabled={disabled}
              onChange={e => onPersonName(p.id, e.target.value)}
              placeholder={t('personContext.personName')}
            />
            <button type="button" style={smallBtn} disabled={disabled} onClick={() => onPersonRemove(p.id)}>
              {t('personContext.removePerson')}
            </button>
          </div>
          <div style={{ marginTop: '6px', display: 'flex', gap: '6px', flexWrap: 'wrap', alignItems: 'center' }}>
            {(p.aliases || []).map(a => (
              <span key={a} style={{ fontSize: '0.8rem', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: '12px', padding: '2px 8px' }}>
                {a}
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => onAliasRemove(p.id, a)}
                  style={{ marginLeft: '6px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-muted)' }}
                  title={t('personContext.removeAlias')}
                >
                  ×
                </button>
              </span>
            ))}
            <AliasInput disabled={disabled} onAdd={alias => onAliasAdd(p.id, alias)} placeholder={t('personContext.addAliasPh')} />
          </div>
        </div>
      ))}

      <div style={{ display: 'flex', gap: '8px', marginBottom: '12px', flexWrap: 'wrap' }}>
        <input
          style={{ ...inputStyle, maxWidth: '220px' }}
          value={newPersonName}
          disabled={disabled}
          onChange={e => setNewPersonName(e.target.value)}
          placeholder={t('personContext.newPersonPh')}
        />
        <button type="button" style={smallBtn} disabled={disabled} onClick={onPersonAdd}>
          {t('personContext.addPerson')}
        </button>
      </div>

      <div style={{ fontWeight: 700, marginBottom: '8px' }}>{t('personContext.relationships')}</div>
      {relationships.length === 0 && (
        <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '8px' }}>
          {t('personContext.noRelations')}
        </div>
      )}
      {relationships.map((r, i) => (
        <div key={`${r.from}-${r.to}-${i}`} style={{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '6px', flexWrap: 'wrap', fontSize: '0.85rem' }}>
          <span>{nameById(people, r.from)} ───</span>
          <input
            style={{ ...inputStyle, maxWidth: '160px' }}
            value={r.relationship}
            disabled={disabled}
            onChange={e => onRelEdit(i, e.target.value)}
          />
          <span>─── {nameById(people, r.to)}</span>
          <button type="button" style={smallBtn} disabled={disabled} onClick={() => onRelRemove(i)}>
            {t('personContext.removeRelation')}
          </button>
        </div>
      ))}

      {people.length >= 2 && (
        <div style={{ display: 'flex', gap: '8px', marginTop: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
          <select style={inputStyle} value={relFrom} disabled={disabled} onChange={e => setRelFrom(e.target.value)}>
            <option value="">{t('personContext.pickFrom')}</option>
            {people.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <select style={inputStyle} value={relTo} disabled={disabled} onChange={e => setRelTo(e.target.value)}>
            <option value="">{t('personContext.pickTo')}</option>
            {people.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <input
            style={{ ...inputStyle, maxWidth: '160px' }}
            value={relDesc}
            disabled={disabled}
            onChange={e => setRelDesc(e.target.value)}
            placeholder={t('personContext.relationPh')}
          />
          <button
            type="button"
            style={smallBtn}
            disabled={disabled}
            onClick={() => { onRelAdd(relFrom || people[0].id, relTo || people[1].id, relDesc); setRelDesc(''); }}
          >
            {t('personContext.addRelation')}
          </button>
        </div>
      )}
    </div>
  );
}

function AliasInput({ onAdd, placeholder, disabled }) {
  const [val, setVal] = useState('');
  return (
    <span style={{ display: 'inline-flex', gap: '4px', alignItems: 'center' }}>
      <input
        style={{ maxWidth: '140px', padding: '4px 8px', fontSize: '0.8rem', borderRadius: '4px', border: '1px solid var(--border)' }}
        value={val}
        disabled={disabled}
        onChange={e => setVal(e.target.value)}
        placeholder={placeholder}
      />
      <button
        type="button"
        disabled={disabled}
        onClick={() => { onAdd(val); setVal(''); }}
        style={{ background: 'none', border: '1px solid var(--border)', borderRadius: '4px', cursor: 'pointer', fontSize: '0.8rem', padding: '4px 8px' }}
      >
        +
      </button>
    </span>
  );
}
