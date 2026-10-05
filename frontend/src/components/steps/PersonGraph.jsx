import React, { useEffect, useMemo, useRef, useState } from 'react';

const W = 1000;
const H = 620;

function colorFor(index) {
  const hues = [210, 160, 28, 0, 270, 190, 130, 330, 55, 240];
  const h = hues[index % hues.length];
  return `hsl(${h}, 60%, 55%)`;
}

/**
 * Zero-dependency interactive SVG graph: nodes = people, edges = relationships.
 * Drag nodes to move, drag background to pan, wheel to zoom,
 * click node / edge to select and edit in the side panel.
 */
export default function PersonGraph({
  people,
  relationships,
  selectedNodeId,
  selectedEdgeIndex,
  connectSourceId,
  onSelectNode,
  onSelectEdge,
  onMoveNode,
  onAutoLayout,
  onBackgroundClick,
  disabled = false
}) {
  const svgRef = useRef(null);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const dragRef = useRef(null);

  const nameById = useMemo(() => {
    const m = {};
    (people || []).forEach(p => { m[p.id] = p.name; });
    return m;
  }, [people]);

  const neighborSets = useMemo(() => {
    const map = {};
    (relationships || []).forEach(r => {
      if (!map[r.from]) map[r.from] = new Set();
      if (!map[r.to]) map[r.to] = new Set();
      map[r.from].add(r.to);
      map[r.to].add(r.from);
    });
    return map;
  }, [relationships]);

  const toSvgPoint = (clientX, clientY) => {
    const svg = svgRef.current;
    if (!svg) return { x: W / 2, y: H / 2 };
    const rect = svg.getBoundingClientRect();
    const vx = ((clientX - rect.left) / rect.width) * W;
    const vy = ((clientY - rect.top) / rect.height) * H;
    return { x: (vx - W / 2 - pan.x) / zoom + W / 2, y: (vy - H / 2 - pan.y) / zoom + H / 2 };
  };

  const handleWheel = (e) => {
    // Allow page scroll with ctrl not pressed? Zoom only on wheel over svg.
    e.preventDefault();
    const delta = e.deltaY > 0 ? 0.9 : 1.1;
    setZoom(z => Math.min(2.5, Math.max(0.5, z * delta)));
  };

  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    svg.addEventListener('wheel', handleWheel, { passive: false });
    return () => svg.removeEventListener('wheel', handleWheel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pan]);

  const onNodePointerDown = (e, id) => {
    if (disabled) return;
    e.stopPropagation();
    (e.target).setPointerCapture?.(e.pointerId);
    dragRef.current = {
      type: 'node', id, startX: e.clientX, startY: e.clientY, moved: false
    };
  };

  const onBackgroundPointerDown = (e) => {
    if (disabled) return;
    dragRef.current = {
      type: 'pan', startX: e.clientX, startY: e.clientY, panX: pan.x, panY: pan.y, moved: false
    };
  };

  const onPointerMove = (e) => {
    const d = dragRef.current;
    if (!d) return;
    const dx = e.clientX - d.startX;
    const dy = e.clientY - d.startY;
    if (Math.abs(dx) + Math.abs(dy) > 4) d.moved = true;
    if (d.type === 'node' && d.moved) {
      const pt = toSvgPoint(e.clientX, e.clientY);
      onMoveNode?.(d.id, pt);
    } else if (d.type === 'pan' && d.moved) {
      const svg = svgRef.current;
      const rect = svg?.getBoundingClientRect();
      const sx = rect ? rect.width / W : 1;
      const sy = rect ? rect.height / H : 1;
      setPan({ x: d.panX + dx / sx, y: d.panY + dy / sy });
    }
  };

  const endDrag = (e) => {
    const d = dragRef.current;
    dragRef.current = null;
    if (!d) return;
    if (!d.moved) {
      if (d.type === 'node') onSelectNode?.(d.id);
      else onBackgroundClick?.();
    }
  };

  const resetView = () => { setPan({ x: 0, y: 0 }); setZoom(1); };

  const activeId = selectedNodeId || connectSourceId;
  const isDimmed = (id) => {
    if (!activeId) return false;
    if (id === activeId) return false;
    return !(neighborSets[activeId] && neighborSets[activeId].has(id));
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '8px', flexWrap: 'wrap' }}>
        <span style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
          拖节点排版 · 拖空白平移 · 滚轮缩放 · 点节点/连线编辑
        </span>
        <span style={{ flex: 1 }} />
        <button type="button" onClick={onAutoLayout} style={chipBtn} disabled={disabled || !onAutoLayout}>自动排布</button>
        <button type="button" onClick={() => setZoom(z => Math.min(2.5, z * 1.2))} style={chipBtn} disabled={disabled}>＋</button>
        <button type="button" onClick={() => setZoom(z => Math.max(0.5, z * 0.85))} style={chipBtn} disabled={disabled}>－</button>
        <button type="button" onClick={resetView} style={chipBtn} disabled={disabled}>重置视图</button>
      </div>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${W} ${H}`}
        style={{
          width: '100%', height: '540px', display: 'block',
          background: 'var(--bg-input)', border: '1px solid var(--border)',
          borderRadius: '8px', cursor: disabled ? 'default' : 'grab',
          touchAction: 'none', userSelect: 'none'
        }}
        onPointerDown={onBackgroundPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={endDrag}
      >
        <g transform={`translate(${W / 2 + pan.x},${H / 2 + pan.y}) scale(${zoom}) translate(${-W / 2},${-H / 2})`}>
          {/* edges */}
          {(relationships || []).map((r, i) => {
            const a = (people || []).find(p => p.id === r.from);
            const b = (people || []).find(p => p.id === r.to);
            if (!a || !b || a.x == null || b.x == null) return null;
            // Trim the line to the circle borders so edges never run underneath
            // the endpoint nodes (r=26, trim 29 for a small gap).
            const dx = b.x - a.x;
            const dy = b.y - a.y;
            const len = Math.hypot(dx, dy) || 0.01;
            const ux = dx / len;
            const uy = dy / len;
            const x1 = a.x + ux * 29;
            const y1 = a.y + uy * 29;
            const x2 = b.x - ux * 29;
            const y2 = b.y - uy * 29;
            const mx = (x1 + x2) / 2;
            const my = (y1 + y2) / 2;
            // Alternate labels to opposite sides of the line so two close /
            // crossing edges don't stack their pills on each other.
            const side = i % 2 === 0 ? 1 : -1;
            const lx = mx + -uy * 13 * side;
            const ly = my + ux * 13 * side;
            const selected = selectedEdgeIndex === i;
            // Long labels are truncated on the canvas (full text in tooltip +
            // side editor) so a verbose relationship never crowds a short edge.
            const fullLabel = r.relationship || '';
            const label = fullLabel.length > 8 ? `${fullLabel.slice(0, 8)}…` : fullLabel;
            const labelW = Math.max(34, label.length * 13 + 16);
            return (
              <g key={`${r.from}-${r.to}-${i}`} opacity={activeId && r.from !== activeId && r.to !== activeId ? 0.25 : 1}>
                {/* wide invisible hit line */}
                <line
                  x1={x1} y1={y1} x2={x2} y2={y2}
                  stroke="transparent" strokeWidth={18}
                  style={{ cursor: disabled ? 'default' : 'pointer' }}
                  onPointerDown={(e) => {
                    if (disabled) return;
                    e.stopPropagation();
                    onSelectEdge?.(i);
                  }}
                />
                <line
                  x1={x1} y1={y1} x2={x2} y2={y2}
                  stroke={selected ? '#f59e0b' : '#94a3b8'}
                  strokeWidth={selected ? 3 : 1.8}
                  opacity={0.9}
                />
                <g
                  transform={`translate(${lx},${ly})`}
                  style={{ cursor: disabled ? 'default' : 'pointer' }}
                  onPointerDown={(e) => {
                    if (disabled) return;
                    e.stopPropagation();
                    onSelectEdge?.(i);
                  }}
                >
                  <rect x={-labelW / 2} y={-11} width={labelW} height={22} rx={11}
                    fill={selected ? '#f59e0b' : 'var(--bg-card)'}
                    stroke={selected ? '#b45309' : 'var(--border)'} />
                  <text textAnchor="middle" dy={4} fontSize={12}
                    fill={selected ? '#fff' : 'var(--text-secondary)'}>
                    {label || '?'}
                  </text>
                </g>
                <title>{`${nameById[r.from] || r.from} — ${r.relationship} — ${nameById[r.to] || r.to}`}</title>
              </g>
            );
          })}
          {/* nodes */}
          {(people || []).map((p, idx) => {
            if (p.x == null) return null;
            const selected = p.id === selectedNodeId;
            const isSource = p.id === connectSourceId;
            const dim = isDimmed(p.id);
            return (
              <g key={p.id} transform={`translate(${p.x},${p.y})`} opacity={dim ? 0.35 : 1}>
                {(selected || isSource) && (
                  <circle r={34} fill="none" stroke={isSource ? '#22c55e' : '#f59e0b'} strokeWidth={2.5} strokeDasharray={isSource ? '6 4' : undefined} />
                )}
                <circle
                  r={26}
                  fill={colorFor(idx)}
                  stroke={selected ? '#7c2d12' : 'rgba(0,0,0,0.15)'}
                  strokeWidth={selected ? 3 : 1.5}
                  style={{ cursor: disabled ? 'default' : 'grab' }}
                  onPointerDown={(e) => onNodePointerDown(e, p.id)}
                />
                <text textAnchor="middle" dy={-2} fontSize={13} fontWeight={700} fill="#fff"
                  style={{ pointerEvents: 'none' }}>
                  {(p.name || '?').slice(0, 6)}
                </text>
                {(p.aliases || []).length > 0 && (
                  <text textAnchor="middle" dy={12} fontSize={10} fill="rgba(255,255,255,0.9)"
                    style={{ pointerEvents: 'none' }}>
                    +{(p.aliases || []).length}
                  </text>
                )}
                <text textAnchor="middle" dy={42} fontSize={12} fontWeight={600} fill="var(--text-primary)"
                  style={{ pointerEvents: 'none' }}>
                  {(p.name || '').length > 10 ? `${(p.name || '').slice(0, 10)}…` : p.name}
                </text>
                <title>{`${p.name}${(p.aliases || []).length ? `（${p.aliases.join('、')}）` : ''}`}</title>
              </g>
            );
          })}
        </g>
      </svg>
    </div>
  );
}

const chipBtn = {
  background: 'none', border: '1px solid var(--border)', borderRadius: '12px',
  cursor: 'pointer', fontSize: '0.75rem', padding: '2px 10px', color: 'var(--text-secondary)'
};
