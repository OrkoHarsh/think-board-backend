-- Seed the template catalog.
--
-- Repeatable migration: Flyway re-runs this whenever its checksum changes, so template content can be
-- corrected without adding a new versioned migration. Upserts on slug, so existing rows are updated
-- in place and boards already created from a template are unaffected.
--
-- Authoring conventions (must match what the canvas persists):
--   x / y are the top-left corner for every object type, as produced by handleAddShape in BoardPage.
--   line and arrow objects sit at x = 0, y = 0 and carry absolute canvas coordinates in points.
--   umlClass height must equal 32 + (max(1, attributes) * 18 + 16) + (max(1, methods) * 18 + 16).
--   Objects must not overlap: board_objects has no explicit ordering, so paint order is not guaranteed.

INSERT INTO templates (slug, name, description, category, definition, sort_order, is_active) VALUES

('kanban',
 'Kanban board',
 'Three lanes to move work from idea to done.',
 'planning',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",   "properties": {"x": 60,  "y": 30,  "width": 420, "height": 44,  "text": "Sprint board",              "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold",   "textDecoration": "", "align": "left"}},
     {"type": "rect",   "properties": {"x": 60,  "y": 100, "width": 240, "height": 44,  "text": "To do",                     "fill": "#334155", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "rect",   "properties": {"x": 360, "y": 100, "width": 240, "height": 44,  "text": "In progress",               "fill": "#B45309", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "rect",   "properties": {"x": 660, "y": 100, "width": 240, "height": 44,  "text": "Done",                      "fill": "#15803D", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 164, "width": 240, "height": 100, "text": "Write the API contract",    "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 284, "width": 240, "height": 100, "text": "Design the empty states",   "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 404, "width": 240, "height": 100, "text": "Spike: rate limiting",      "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 360, "y": 164, "width": 240, "height": 100, "text": "Board templates",           "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 360, "y": 284, "width": 240, "height": 100, "text": "Fix the drag jitter",       "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 660, "y": 164, "width": 240, "height": 100, "text": "Auth hardening",            "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 660, "y": 284, "width": 240, "height": 100, "text": "Dashboard redesign",        "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}}
   ]
 }'::jsonb,
 10, TRUE),

('retrospective',
 'Retrospective',
 'Start, stop, continue: close out a sprint as a team.',
 'collaboration',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",   "properties": {"x": 60,  "y": 30,  "width": 460, "height": 44,  "text": "Sprint retrospective",          "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold",   "textDecoration": "", "align": "left"}},
     {"type": "rect",   "properties": {"x": 60,  "y": 100, "width": 240, "height": 44,  "text": "Start",                         "fill": "#15803D", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "rect",   "properties": {"x": 360, "y": 100, "width": 240, "height": 44,  "text": "Stop",                          "fill": "#B91C1C", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "rect",   "properties": {"x": 660, "y": 100, "width": 240, "height": 44,  "text": "Continue",                      "fill": "#1D4ED8", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 164, "width": 240, "height": 110, "text": "What should we try next?",      "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 294, "width": 240, "height": 110, "text": "",                              "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 360, "y": 164, "width": 240, "height": 110, "text": "What slowed us down?",          "fill": "#FEE2E2", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 360, "y": 294, "width": 240, "height": 110, "text": "",                              "fill": "#FEE2E2", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 660, "y": 164, "width": 240, "height": 110, "text": "What worked well?",             "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 660, "y": 294, "width": 240, "height": 110, "text": "",                              "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}}
   ]
 }'::jsonb,
 20, TRUE),

('brainstorm',
 'Brainstorm grid',
 'A topic banner and a wall of notes to fill in together.',
 'collaboration',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",   "properties": {"x": 60,  "y": 30,  "width": 420, "height": 44,  "text": "Brainstorm",         "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold",   "textDecoration": "", "align": "left"}},
     {"type": "rect",   "properties": {"x": 60,  "y": 100, "width": 896, "height": 56,  "text": "Topic: replace me",  "fill": "#EDE9FE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold",   "textDecoration": "", "align": "center"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 186, "width": 200, "height": 140, "text": "",                   "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 292, "y": 186, "width": 200, "height": 140, "text": "",                   "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 524, "y": 186, "width": 200, "height": 140, "text": "",                   "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 756, "y": 186, "width": 200, "height": 140, "text": "",                   "fill": "#FCE7F3", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 60,  "y": 358, "width": 200, "height": 140, "text": "",                   "fill": "#FCE7F3", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 292, "y": 358, "width": 200, "height": 140, "text": "",                   "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 524, "y": 358, "width": 200, "height": 140, "text": "",                   "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}},
     {"type": "sticky", "properties": {"x": 756, "y": 358, "width": 200, "height": 140, "text": "",                   "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "normal", "textDecoration": "", "align": "left"}}
   ]
 }'::jsonb,
 30, TRUE),

('impact-effort',
 'Impact vs effort',
 'Sort ideas into quick wins, big bets, fill-ins and time sinks.',
 'planning',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text", "properties": {"x": 60,  "y": 30,  "width": 460, "height": 44,  "text": "Impact vs effort", "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "text", "properties": {"x": 10,  "y": 110, "width": 120, "height": 36,  "text": "Impact",           "fill": "#6B7280", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "text", "properties": {"x": 440, "y": 612, "width": 200, "height": 36,  "text": "Effort",           "fill": "#6B7280", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "rect", "properties": {"x": 140, "y": 120, "width": 300, "height": 240, "text": "Quick wins",       "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect", "properties": {"x": 440, "y": 120, "width": 300, "height": 240, "text": "Big bets",         "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect", "properties": {"x": 140, "y": 360, "width": 300, "height": 240, "text": "Fill-ins",         "fill": "#FEF9C3", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect", "properties": {"x": 440, "y": 360, "width": 300, "height": 240, "text": "Time sinks",       "fill": "#FEE2E2", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold", "textDecoration": "", "align": "center"}}
   ]
 }'::jsonb,
 40, TRUE),

('system-architecture',
 'System architecture',
 'Client, gateway, services and datastores, already wired up.',
 'engineering',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",  "properties": {"x": 60,  "y": 30,  "width": 500, "height": 44, "text": "System architecture",  "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "rect",  "properties": {"x": 380, "y": 110, "width": 220, "height": 76, "text": "Web client",           "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 380, "y": 250, "width": 220, "height": 76, "text": "API gateway",          "fill": "#BFDBFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 170, "y": 390, "width": 210, "height": 76, "text": "Auth service",         "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 600, "y": 390, "width": 210, "height": 76, "text": "Board service",        "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "icon",  "properties": {"x": 243, "y": 530, "width": 64,  "height": 64, "iconKey": "logos:postgresql", "label": "PostgreSQL"}},
     {"type": "icon",  "properties": {"x": 673, "y": 530, "width": 64,  "height": 64, "iconKey": "logos:redis",      "label": "Redis"}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [490, 186, 490, 250], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [420, 326, 290, 390], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [560, 326, 690, 390], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [275, 466, 275, 530], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [705, 466, 705, 530], "stroke": "#475569", "strokeWidth": 2.5}}
   ]
 }'::jsonb,
 50, TRUE),

('microservices',
 'Microservices layout',
 'Gateway, four services, an event bus and two datastores.',
 'engineering',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",  "properties": {"x": 60,  "y": 30,  "width": 500, "height": 44, "text": "Microservices",          "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "rect",  "properties": {"x": 400, "y": 110, "width": 240, "height": 72, "text": "API gateway",            "fill": "#BFDBFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 60,  "y": 270, "width": 200, "height": 76, "text": "Users",                  "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 300, "y": 270, "width": 200, "height": 76, "text": "Orders",                 "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 540, "y": 270, "width": 200, "height": 76, "text": "Payments",               "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 780, "y": 270, "width": 200, "height": 76, "text": "Notifications",          "fill": "#E0E7FF", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",  "properties": {"x": 60,  "y": 420, "width": 920, "height": 64, "text": "Event bus",              "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 18, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "icon",  "properties": {"x": 300, "y": 540, "width": 64,  "height": 64, "iconKey": "logos:postgresql", "label": "PostgreSQL"}},
     {"type": "icon",  "properties": {"x": 700, "y": 540, "width": 64,  "height": 64, "iconKey": "logos:redis",      "label": "Redis"}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [520, 182, 160, 270], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [520, 182, 400, 270], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [520, 182, 640, 270], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [520, 182, 880, 270], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [160, 346, 160, 420], "stroke": "#94A3B8", "strokeWidth": 2}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [400, 346, 400, 420], "stroke": "#94A3B8", "strokeWidth": 2}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [640, 346, 640, 420], "stroke": "#94A3B8", "strokeWidth": 2}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [880, 346, 880, 420], "stroke": "#94A3B8", "strokeWidth": 2}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [332, 484, 332, 540], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [732, 484, 732, 540], "stroke": "#475569", "strokeWidth": 2.5}}
   ]
 }'::jsonb,
 60, TRUE),

('uml-class',
 'UML class diagram',
 'Three related classes to rename and extend.',
 'engineering',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",     "properties": {"x": 60, "y": 30, "width": 500, "height": 44, "text": "Domain model", "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "umlClass", "properties": {"x": 100, "y": 140, "width": 240, "height": 154, "fill": "#FFFFFF", "stroke": "#94A3B8", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 13,
       "className": "User",
       "attributes": ["- id: UUID", "- email: String", "- name: String"],
       "methods": ["+ login(): Token", "+ boards(): List"]}},
     {"type": "umlClass", "properties": {"x": 480, "y": 140, "width": 240, "height": 136, "fill": "#FFFFFF", "stroke": "#94A3B8", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 13,
       "className": "Board",
       "attributes": ["- id: UUID", "- title: String", "- ownerId: UUID"],
       "methods": ["+ addObject(o): void"]}},
     {"type": "umlClass", "properties": {"x": 480, "y": 400, "width": 240, "height": 136, "fill": "#FFFFFF", "stroke": "#94A3B8", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 13,
       "className": "BoardObject",
       "attributes": ["- id: String", "- type: String"],
       "methods": ["+ move(x, y): void", "+ resize(w, h): void"]}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [340, 217, 480, 217], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow", "properties": {"x": 0, "y": 0, "points": [600, 276, 600, 400], "stroke": "#475569", "strokeWidth": 2.5}}
   ]
 }'::jsonb,
 70, TRUE),

('flowchart',
 'Flowchart',
 'Start, a decision and two branches, ready to relabel.',
 'engineering',
 '{
   "schemaVersion": 1,
   "objects": [
     {"type": "text",    "properties": {"x": 60,  "y": 30,  "width": 500, "height": 44,  "text": "Request flow",     "fill": "#111827", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 28, "fontStyle": "bold", "textDecoration": "", "align": "left"}},
     {"type": "ellipse", "properties": {"x": 380, "y": 110, "width": 180, "height": 80,  "text": "Start",            "fill": "#D1FAE5", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",    "properties": {"x": 380, "y": 240, "width": 180, "height": 76,  "text": "Validate input",   "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "diamond", "properties": {"x": 370, "y": 370, "width": 200, "height": 140, "text": "Valid?",           "fill": "#FEF3C7", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",    "properties": {"x": 660, "y": 402, "width": 190, "height": 76,  "text": "Process request",  "fill": "#DBEAFE", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "rect",    "properties": {"x": 90,  "y": 402, "width": 190, "height": 76,  "text": "Return error",     "fill": "#FEE2E2", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 15, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "ellipse", "properties": {"x": 380, "y": 580, "width": 180, "height": 80,  "text": "End",              "fill": "#E5E7EB", "fontFamily": "Geist Sans, system-ui, sans-serif", "fontSize": 16, "fontStyle": "bold", "textDecoration": "", "align": "center"}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [470, 190, 470, 240], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [470, 316, 470, 370], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [570, 440, 660, 440], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [370, 440, 280, 440], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [755, 478, 560, 615], "stroke": "#475569", "strokeWidth": 2.5}},
     {"type": "arrow",   "properties": {"x": 0, "y": 0, "points": [185, 478, 380, 615], "stroke": "#475569", "strokeWidth": 2.5}}
   ]
 }'::jsonb,
 80, TRUE)

ON CONFLICT (slug) DO UPDATE SET
    name        = EXCLUDED.name,
    description = EXCLUDED.description,
    category    = EXCLUDED.category,
    definition  = EXCLUDED.definition,
    sort_order  = EXCLUDED.sort_order,
    is_active   = EXCLUDED.is_active,
    updated_at  = now();
