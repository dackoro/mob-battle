package dev.dackoro.mobbattle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Server-side software renderer for .blockymodel files (dev prototype, 2026-10-16, playtest: a thumbnail of ANY mob, modded
 * ones too, for a Smash-like VS screen). No Hytale classes: it takes the model JSON and its textures, so it also runs
 * offline. Geometry follows Blockbench's Hytale plugin (hytale_plugin.js, codec parse / parseNode) and Blockbench's
 * own cube code (Cube.preview_controller.updateGeometry / updateUV, read live through the Blockbench MCP):
 * <ul>
 *   <li>a node with a shape that is not a static box is a group: pivot (origin) = its position + its parent's shape
 *   offset + its parent group's origin (absolute), rotated by its quaternion about that pivot;</li>
 *   <li>its box goes from origin + offset - size/2 to origin + offset + size/2, scaled by stretch about its center;
 *   a quad has 0 size on its normal axis and only its normal face, textured with the "front" layout;</li>
 *   <li>a static box has no group: the box carries the node's own rotation about the node's origin;</li>
 *   <li>face corners in Blockbench's vertex order get UVs (u1,v1) (u2,v1) (u1,v2) (u2,v2), rotated a step per 90
 *   degrees of the face angle; the UV rectangle comes from the Hytale texture layout (offset, mirror, angle);</li>
 *   <li>an attachment node marked isPiece hangs on the base group of the same name, at that group's main box center,
 *   with no rotation of its own, and its children ignore its offset.</li>
 * </ul>
 * Rendering: orthographic, z-buffer, nearest texel, alpha cutoff 0.5, back faces culled unless doubleSided, light
 * shading per face direction (top bright, bottom dark). Bind pose (no animation). Parts far from the model's main mass
 * (attack effects kept in the model, e.g. the Nightlord's orb and beam) are left out of the picture.
 */
final class ModelThumbnail {

    /** One model file and the texture its faces sample (the base model, or an attachment). */
    record Part(String modelJson, BufferedImage texture) {
    }

    /** One frame of a .blockyanim: its JSON and the time as a share of its duration (0-1). */
    record Pose(String animJson, double t) {
    }

    /** Camera direction from the model toward the camera (three-quarter view, the left one of mob_step.js). */
    static final double[] THREE_QUARTER = {-0.62, 0.3, 1};
    /** Three-quarter from the other side (the model looks to the left of the picture). */
    static final double[] THREE_QUARTER_RIGHT = {0.62, 0.3, 1};
    static final double[] FRONT = {0, 0.15, 1};
    /** Side views: the model seen in profile. */
    static final double[] PROFILE_LEFT = {-1, 0.18, 0.12};
    static final double[] PROFILE_RIGHT = {1, 0.18, 0.12};

    private ModelThumbnail() {
    }

    // ---- model ----

    private static final class Group {
        final String name;
        final double[] origin;
        final double[] quat;      // x, y, z, w
        final Group parent;
        final boolean visible;
        double[] mainCenter;      // center of its first unrotated box, for attachments
        double[] animQuat = {0, 0, 0, 1};   // one animation frame: extra rotation about the pivot,
        double[] animPos = {0, 0, 0};        // a move in the parent's space,
        double[] animStretch = {1, 1, 1};    // its own boxes stretched,
        boolean animHidden;                  // and hidden (with everything under it)
        /** An attachment piece hung on the base bone of its name: it follows that bone, never animated itself. */
        boolean piece;

        Group(String name, double[] origin, double[] quat, Group parent, boolean visible) {
            this.name = name;
            this.origin = origin;
            this.quat = quat;
            this.parent = parent;
            this.visible = visible && (parent == null || parent.visible);
        }
    }

    private static final class Box {
        double[] from, to;
        double[] ownOrigin, ownQuat;   // static boxes only
        Group group;
        boolean doubleSided;
        /** A quad (decal): drawn a hair in front, so one lying on a box face wins the depth tie. */
        boolean quad;
        double[] center;   // model space, set at render
        final float[][][] uv = new float[6][][];   // per face (BB order east, west, up, down, south, north): 4 corners
        int texture;
    }

    /** BB face order and the Hytale texture layout key of each. */
    private static final String[] BB_FACES = {"east", "west", "up", "down", "south", "north"};
    private static final Map<String, String> HYTALE_OF = Map.of("east", "right", "west", "left", "up", "top",
            "down", "bottom", "south", "front", "north", "back");
    private static final Map<String, String> NORMAL_FACE = Map.of("-X", "west", "+X", "east", "-Y", "down",
            "+Y", "up", "-Z", "north", "+Z", "south");

    private static final class Model {
        final List<Group> groups = new ArrayList<>();
        final List<Box> boxes = new ArrayList<>();
    }

    /** Parses the base model (attachment false) or an attachment onto the groups parsed so far. */
    private static void parse(Model m, String json, int texture, boolean attachment) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray nodes = root.has("nodes") ? root.getAsJsonArray("nodes") : new JsonArray();
        List<Group> existing = new ArrayList<>(m.groups);
        for (JsonElement n : nodes) parseNode(m, n.getAsJsonObject(), false, null, null, texture, attachment, existing);
    }

    private static void parseNode(Model m, JsonObject node, boolean hasParentNode, Group parentGroup, double[] parentOffset,
                                  int texture, boolean attachment, List<Group> existing) {
        JsonObject shape = node.has("shape") && node.get("shape").isJsonObject() ? node.getAsJsonObject("shape") : new JsonObject();
        JsonObject settings = shape.has("settings") && shape.get("settings").isJsonObject() ? shape.getAsJsonObject("settings") : new JsonObject();
        boolean isPiece = settings.has("isPiece") && settings.get("isPiece").getAsBoolean();
        String name = node.has("name") ? node.get("name").getAsString() : "";
        if (attachment && isPiece) {
            for (Group g : existing) {
                if (g.name.equals(name)) {
                    parentGroup = g;
                    hasParentNode = false;
                    break;
                }
            }
        }
        double[] quat = quat(node.get("orientation"));
        double[] offset = vec(shape.get("offset"), 0);
        double[] origin = vec(node.get("position"), 0);
        boolean attachedRoot = false;
        if (attachment && !hasParentNode && parentGroup != null) {
            origin = (parentGroup.mainCenter != null ? parentGroup.mainCenter : parentGroup.origin).clone();
            attachedRoot = true;
        } else if (parentOffset != null && parentGroup != null) {
            origin = add(add(origin, parentOffset), parentGroup.origin);
        }
        boolean visible = !shape.has("visible") || shape.get("visible").getAsBoolean();
        boolean staticBox = settings.has("isStaticBox") && settings.get("isStaticBox").getAsBoolean();
        Group group = null;
        if (!staticBox) {
            group = new Group(name, origin, attachedRoot ? new double[] {0, 0, 0, 1} : quat, parentGroup, visible);
            group.piece = attachedRoot;
            m.groups.add(group);
        }
        String type = shape.has("type") ? shape.get("type").getAsString() : "none";
        if (!"none".equals(type) && settings.has("size")) {
            double[] size = vec(settings.get("size"), 0);
            String normalFace = null;
            if ("quad".equals(type)) {
                String normal = settings.has("normal") ? settings.get("normal").getAsString() : "+Z";
                char axis = normal.length() > 1 ? normal.charAt(1) : 'Z';
                if (axis == 'X') size = new double[] {0, size[1], size[0]};
                else if (axis == 'Y') size = new double[] {size[0], 0, size[1]};
                else size = new double[] {size[0], size[1], 0};
                normalFace = NORMAL_FACE.get(normal);
            }
            double[] stretch = vec(shape.get("stretch"), 1);
            double[] center = add(origin, offset);
            Box b = new Box();
            b.from = new double[3];
            b.to = new double[3];
            for (int i = 0; i < 3; i++) {
                double half = size[i] / 2 * stretch[i];
                b.from[i] = center[i] - half;
                b.to[i] = center[i] + half;
                if (b.from[i] == b.to[i]) b.to[i] += 0.001;
            }
            b.texture = texture;
            b.quad = normalFace != null;
            b.doubleSided = shape.has("doubleSided") && shape.get("doubleSided").getAsBoolean();
            if (group != null) {
                b.group = group;
                if (group.mainCenter == null) group.mainCenter = center.clone();
            } else {
                b.group = parentGroup;
                b.ownOrigin = origin;
                b.ownQuat = quat;
            }
            JsonObject layout = shape.has("textureLayout") && shape.get("textureLayout").isJsonObject()
                    ? shape.getAsJsonObject("textureLayout") : new JsonObject();
            double[] rawSize = vec(settings.get("size"), 0);
            double[] uvSize3 = "quad".equals(type) ? size : rawSize;
            if (normalFace == null) layout = withDefaultFaces(layout, rawSize);
            for (int f = 0; f < 6; f++) {
                String face = BB_FACES[f];
                String key = HYTALE_OF.get(face);
                JsonObject src = layout.has(key) && layout.get(key).isJsonObject() ? layout.getAsJsonObject(key) : null;
                if (normalFace != null) src = face.equals(normalFace) && layout.has("front") ? layout.getAsJsonObject("front") : null;
                if (src == null) continue;
                b.uv[f] = faceUv(src, key, uvSize3);
            }
            if (visible && (group == null || group.visible) && (parentGroup == null || parentGroup.visible)) m.boxes.add(b);
        }
        if (node.has("children") && node.get("children").isJsonArray() && group != null) {
            double[] childOffset = attachment && isPiece ? new double[3] : offset;
            for (JsonElement c : node.getAsJsonArray("children")) {
                parseNode(m, c.getAsJsonObject(), true, group, childOffset, texture, attachment, existing);
            }
        }
    }

    /**
     * A box whose listed faces all sit at the standard box unwrap (top (d,0), bottom (d+w,0), left (0,d), right (d+w,d),
     * back (2d+w,d), front (d,d); w, h, d = size x, y, z) gets its missing faces at their standard spots too
     * (2026-10-16, playtest: the Outlanders came out faceless): Player_With_Face's head lists no front, and the Outlander
     * texture paints the face exactly there (with holes for the Eyes attachment), so the game draws it. A box with any
     * face off the pattern keeps its missing faces hidden. ❓ Inferred from the textures, not from the client.
     */
    private static JsonObject withDefaultFaces(JsonObject layout, double[] size) {
        if (layout.size() == 0 || layout.size() >= 6) return layout;
        double w = size[0], h = size[1], d = size[2];
        java.util.Map<String, double[]> std = java.util.Map.of("top", new double[] {d, 0}, "bottom", new double[] {d + w, 0},
                "left", new double[] {0, d}, "right", new double[] {d + w, d}, "back", new double[] {2 * d + w, d},
                "front", new double[] {d, d});
        for (String key : layout.keySet()) {
            double[] at = std.get(key);
            JsonElement f = layout.get(key);
            if (at == null || !f.isJsonObject()) return layout;
            double[] off = vec2(f.getAsJsonObject().get("offset"));
            if (Math.abs(off[0] - at[0]) > 0.01 || Math.abs(off[1] - at[1]) > 0.01) return layout;
        }
        JsonObject out = layout.deepCopy();
        for (java.util.Map.Entry<String, double[]> e : std.entrySet()) {
            if (out.has(e.getKey())) continue;
            JsonObject face = new JsonObject();
            JsonObject off = new JsonObject();
            off.addProperty("x", e.getValue()[0]);
            off.addProperty("y", e.getValue()[1]);
            face.add("offset", off);
            out.add(e.getKey(), face);
        }
        return out;
    }

    /** The 4 corner UVs (texture pixels) of a face, in BB vertex order, from the Hytale layout (hytale_plugin.js). */
    private static float[][] faceUv(JsonObject src, String key, double[] size) {
        double[] off = vec2(src.get("offset"));
        JsonObject mir = src.has("mirror") && src.get("mirror").isJsonObject() ? src.getAsJsonObject("mirror") : new JsonObject();
        double[] mirror = {mir.has("x") && mir.get("x").getAsBoolean() ? -1 : 1, mir.has("y") && mir.get("y").getAsBoolean() ? -1 : 1};
        int angle = src.has("angle") ? (int) Math.round(src.get("angle").getAsDouble()) : 0;
        double[] uvSize = {size[0], size[1]};
        switch (key) {
            case "left", "right" -> uvSize[0] = size[2];
            case "top", "bottom" -> uvSize[1] = size[2];
            default -> { }
        }
        double[] r;
        switch (((angle % 360) + 360) % 360) {
            case 90 -> {
                swap(uvSize);
                swap(mirror);
                mirror[0] *= -1;
                r = new double[] {off[0], off[1] + uvSize[1] * mirror[1], off[0] + uvSize[0] * mirror[0], off[1]};
            }
            case 270 -> {
                swap(uvSize);
                swap(mirror);
                mirror[1] *= -1;
                r = new double[] {off[0] + uvSize[0] * mirror[0], off[1], off[0], off[1] + uvSize[1] * mirror[1]};
            }
            case 180 -> {
                mirror[0] *= -1;
                mirror[1] *= -1;
                r = new double[] {off[0] + uvSize[0] * mirror[0], off[1] + uvSize[1] * mirror[1], off[0], off[1]};
            }
            default -> r = new double[] {off[0], off[1], off[0] + uvSize[0] * mirror[0], off[1] + uvSize[1] * mirror[1]};
        }
        // Blockbench Cube.preview_controller.updateUV: corners, then one shift per 90 degrees of face rotation.
        float[][] c = {{(float) r[0], (float) r[1]}, {(float) r[2], (float) r[1]}, {(float) r[0], (float) r[3]}, {(float) r[2], (float) r[3]}};
        for (int h = ((angle % 360) + 360) % 360; h > 0; h -= 90) {
            float[] t = c[0];
            c[0] = c[2];
            c[2] = c[3];
            c[3] = c[1];
            c[1] = t;
        }
        return c;
    }

    /** The 4 corners of a box face, in Blockbench's vertex order (THREE geometry setShape). */
    private static double[][] corners(int face, double[] i, double[] e) {
        return switch (face) {
            case 0 -> new double[][] {{e[0], e[1], e[2]}, {e[0], e[1], i[2]}, {e[0], i[1], e[2]}, {e[0], i[1], i[2]}};
            case 1 -> new double[][] {{i[0], e[1], i[2]}, {i[0], e[1], e[2]}, {i[0], i[1], i[2]}, {i[0], i[1], e[2]}};
            case 2 -> new double[][] {{i[0], e[1], i[2]}, {e[0], e[1], i[2]}, {i[0], e[1], e[2]}, {e[0], e[1], e[2]}};
            case 3 -> new double[][] {{i[0], i[1], e[2]}, {e[0], i[1], e[2]}, {i[0], i[1], i[2]}, {e[0], i[1], i[2]}};
            case 4 -> new double[][] {{i[0], e[1], e[2]}, {e[0], e[1], e[2]}, {i[0], i[1], e[2]}, {e[0], i[1], e[2]}};
            default -> new double[][] {{e[0], e[1], i[2]}, {i[0], e[1], i[2]}, {e[0], i[1], i[2]}, {i[0], i[1], i[2]}};
        };
    }

    /** Model space of a point of a box: its own rotation (static box), then every group's up to the root. */
    private static double[] world(Box b, double[] p) {
        double[] q = p;
        if (b.ownQuat != null) q = add(b.ownOrigin, rotate(b.ownQuat, sub(q, b.ownOrigin)));
        for (Group g = b.group; g != null; g = g.parent) {
            q = add(add(g.origin, g.animPos), rotate(g.quat, rotate(g.animQuat, sub(q, g.origin))));
        }
        return q;
    }

    // ---- animation (one frame) ----

    private static final double[] ONE = {1, 1, 1};
    /** .blockyanim keyframe times are in frames at 60 per second (hytale_plugin.js FPS). */
    private static final double FPS = 60;

    /**
     * One frame of the animation on every group with the animated node's name (as hytale_plugin.js
     * parseAnimationFile + copyAnimationToGroupsWithSameName): orientation deltas rotate the group about its pivot
     * after its own rotation, position deltas move it in its parent's space, shapeStretch stretches its own boxes,
     * shapeVisible false hides it and everything under it. Between keyframes: linear, or smoothstep for "smooth";
     * before the first / after the last keyframe the nearest one holds.
     */
    private static void applyPose(Model m, Pose pose) {
        JsonObject anim = JsonParser.parseString(pose.animJson()).getAsJsonObject();
        double duration = anim.has("duration") ? anim.get("duration").getAsDouble() : FPS;
        double time = Math.max(0, Math.min(1, pose.t())) * duration;
        // a looping animation (holdLastKeyframe false) goes from its last keyframe back to its first, as in game
        double loop = anim.has("holdLastKeyframe") && anim.get("holdLastKeyframe").getAsBoolean() ? 0 : duration;
        JsonObject nodes = anim.has("nodeAnimations") && anim.get("nodeAnimations").isJsonObject()
                ? anim.getAsJsonObject("nodeAnimations") : new JsonObject();
        for (Group g : m.groups) {
            // an attachment piece (a hood on "Head") already moves with its bone: animating it too turned it twice as
            // far, so hoods and helmets covered the face in every animated pose (playtest 2026-10-16)
            if (g.piece) continue;
            if (!nodes.has(g.name) || !nodes.get(g.name).isJsonObject()) continue;
            // a node inside one of the same name (BetterMobExpansion's leopard: "Head" in "Head", the forearms too) is
            // carried by it: animating both turned the inner one twice, a second head beside the first (playtest,
            // 2026-10-17); only the outermost of the name takes the animation
            if (insideSameName(g)) continue;
            JsonObject n = nodes.getAsJsonObject(g.name);
            double[] q = sample(n, "orientation", time, true, loop);
            if (q != null) g.animQuat = normQ(q);
            double[] p = sample(n, "position", time, false, loop);
            if (p != null) g.animPos = p;
            double[] st = sample(n, "shapeStretch", time, false, loop);
            if (st != null) g.animStretch = st;
            Boolean vis = sampleVisible(n, time);
            if (vis != null) g.animHidden = !vis;
        }
    }

    /** Whether a group above {@code g} (not an attachment piece) has the same name. */
    private static boolean insideSameName(Group g) {
        for (Group up = g.parent; up != null; up = up.parent) {
            if (!up.piece && g.name.equals(up.name)) return true;
        }
        return false;
    }

    /** A vector channel ({x,y,z} or {x,y,z,w}) at {@code time} frames, or null if it has no keyframe. */
    private static double[] sample(JsonObject node, String channel, double time, boolean quaternion, double loop) {
        if (!node.has(channel) || !node.get(channel).isJsonArray()) return null;
        JsonArray kfs = node.getAsJsonArray(channel);
        if (kfs.isEmpty()) return null;
        JsonObject a = null, b = null;
        for (JsonElement e : kfs) {
            JsonObject k = e.getAsJsonObject();
            if (k.get("time").getAsDouble() <= time) a = k;
            else if (b == null) b = k;
        }
        double ta, tb;
        if (a != null && b != null) {
            ta = a.get("time").getAsDouble();
            tb = b.get("time").getAsDouble();
        } else if (loop > 0 && kfs.size() > 1) {
            // past the last keyframe (or before the first) of a looping animation: on its way from the last back to the
            // first (2026-10-16, playtest: the flipbook's punch restarted before the arm came back)
            JsonObject first = kfs.get(0).getAsJsonObject(), last = kfs.get(kfs.size() - 1).getAsJsonObject();
            double tf = first.get("time").getAsDouble(), tl = last.get("time").getAsDouble();
            a = last;
            b = first;
            if (time >= tl) {
                ta = tl;
                tb = tf + loop;
            } else {
                ta = tl - loop;
                tb = tf;
            }
        } else {
            return vecOf((a != null ? a : b).getAsJsonObject("delta"), quaternion);
        }
        double f = tb > ta ? Math.max(0, Math.min(1, (time - ta) / (tb - ta))) : 0;
        if (a.has("interpolationType") && "smooth".equals(a.get("interpolationType").getAsString())) f = f * f * (3 - 2 * f);
        double[] va = vecOf(a.getAsJsonObject("delta"), quaternion), vb = vecOf(b.getAsJsonObject("delta"), quaternion);
        if (quaternion) return nlerp(va, vb, f);
        return new double[] {va[0] + (vb[0] - va[0]) * f, va[1] + (vb[1] - va[1]) * f, va[2] + (vb[2] - va[2]) * f};
    }

    private static Boolean sampleVisible(JsonObject node, double time) {
        if (!node.has("shapeVisible") || !node.get("shapeVisible").isJsonArray()) return null;
        Boolean v = null;
        for (JsonElement e : node.getAsJsonArray("shapeVisible")) {
            JsonObject k = e.getAsJsonObject();
            if (v == null || k.get("time").getAsDouble() <= time) v = k.get("delta").getAsBoolean();
        }
        return v;
    }

    private static double[] vecOf(JsonObject d, boolean quaternion) {
        if (quaternion) return new double[] {num(d, "x", 0), num(d, "y", 0), num(d, "z", 0), num(d, "w", 1)};
        return new double[] {num(d, "x", 0), num(d, "y", 0), num(d, "z", 0)};
    }

    private static double[] nlerp(double[] a, double[] b, double f) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double s = d < 0 ? -1 : 1;
        return normQ(new double[] {a[0] + (s * b[0] - a[0]) * f, a[1] + (s * b[1] - a[1]) * f,
                a[2] + (s * b[2] - a[2]) * f, a[3] + (s * b[3] - a[3]) * f});
    }

    private static double[] normQ(double[] q) {
        double l = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return l < 1e-9 ? new double[] {0, 0, 0, 1} : new double[] {q[0] / l, q[1] / l, q[2] / l, q[3] / l};
    }

    private static boolean isHead(Group g) {
        return !g.piece && "head".equalsIgnoreCase(g.name);
    }

    /** A group under a "Head" bone (or the bone itself), with no attachment piece on the way. */
    private static boolean underHead(Group g) {
        for (; g != null; g = g.parent) {
            if (g.piece) return false;
            if ("head".equalsIgnoreCase(g.name)) return true;
        }
        return false;
    }

    private static boolean hiddenByPose(Box b) {
        for (Group g = b.group; g != null; g = g.parent) if (g.animHidden) return true;
        return false;
    }

    private static void stretchBox(Box b, double[] st) {
        for (int i = 0; i < 3; i++) {
            double c = (b.from[i] + b.to[i]) / 2, h = (b.to[i] - b.from[i]) / 2 * st[i];
            b.from[i] = c - h;
            b.to[i] = c + h;
        }
    }

    // ---- render ----

    /**
     * Renders the parts (base model first, then attachments) seen from {@code dir} to an image whose longest side is
     * {@code size} px (transparent background, trimmed to the model). Null if nothing is visible.
     */
    static BufferedImage render(List<Part> parts, int size, double[] dir, boolean shade) {
        return render(parts, null, size, dir, shade);
    }

    /** Same, posed with one animation frame ({@code pose} null = bind pose). */
    /**
     * The head's position (x, y in the picture's pixels) in the last render() of this thread, or null (no "Head" bone).
     */
    static final ThreadLocal<double[]> LAST_HEAD = new ThreadLocal<>();

    /** Pixels per model unit in the last render() of this thread (64 units = 1 block at scale 1), or null. */
    static final ThreadLocal<Double> LAST_SCALE = new ThreadLocal<>();

    /** How much of a vertical length shows on screen from this view direction (the camera's up axis against the world's). */
    static double upFactor(double[] dir) {
        double[] z = norm(dir);
        double[] x = norm(cross(new double[] {0, 1, 0}, z));
        return Math.abs(cross(z, x)[1]);
    }

    static BufferedImage render(List<Part> parts, Pose pose, int size, double[] dir, boolean shade) {
        Model m = new Model();
        for (int i = 0; i < parts.size(); i++) parse(m, parts.get(i).modelJson(), i, i > 0);
        if (m.boxes.isEmpty()) return null;
        if (pose != null && pose.animJson() != null) applyPose(m, pose);

        // camera basis (three.js lookAt): z toward the camera, x = up × z, y = z × x
        double[] z = norm(dir);
        double[] x = norm(cross(new double[] {0, 1, 0}, z));
        double[] y = cross(z, x);

        // faces in model space; leave out boxes far from the main mass
        List<double[][]> quads = new ArrayList<>();
        List<Box> owners = new ArrayList<>();
        List<Integer> faceIdx = new ArrayList<>();
        List<double[]> centers = new ArrayList<>();
        for (Box b : m.boxes) {
            if (b.group != null && !java.util.Arrays.equals(b.group.animStretch, ONE)) stretchBox(b, b.group.animStretch);
            double[] c = world(b, new double[] {(b.from[0] + b.to[0]) / 2, (b.from[1] + b.to[1]) / 2, (b.from[2] + b.to[2]) / 2});
            b.center = c;
            centers.add(c);
        }
        boolean[] keep = keepMainMass(centers);
        for (int bi = 0; bi < m.boxes.size(); bi++) {
            if (!keep[bi]) continue;
            Box b = m.boxes.get(bi);
            if (hiddenByPose(b)) continue;
            for (int f = 0; f < 6; f++) {
                if (b.uv[f] == null) continue;
                double[][] cs = corners(f, b.from, b.to);
                double[][] w = new double[4][];
                for (int k = 0; k < 4; k++) w[k] = world(b, cs[k]);
                quads.add(w);
                owners.add(b);
                faceIdx.add(f);
            }
        }
        if (quads.isEmpty()) return null;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[][] q : quads) {
            for (double[] p : q) {
                double sx = dot(p, x), sy = dot(p, y);
                minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
            }
        }
        double spanX = Math.max(1e-6, maxX - minX), spanY = Math.max(1e-6, maxY - minY);
        double scale = (size - 4) / Math.max(spanX, spanY);
        LAST_SCALE.set(scale);
        int w = Math.max(1, (int) Math.ceil(spanX * scale) + 4), h = Math.max(1, (int) Math.ceil(spanY * scale) + 4);
        double ox = -minX * scale + 2, oy = maxY * scale + 2;   // screen y grows downward
        // where the head lands in the picture (centers of the "Head" bone's boxes), for framing on the face
        // A Head bone with no box of its own (Dragon_Frost: forehead and jaws hang under it) takes its whole subtree,
        // attachment pieces left out (playtest 2026-10-16: the dragon's face was not in its team column)
        double hx = 0, hy = 0;
        int hn = 0;
        for (int pass = 0; pass < 2 && hn == 0; pass++) {
            for (Box b : m.boxes) {
                if (b.group == null || b.center == null || !(pass == 0 ? isHead(b.group) : underHead(b.group))) continue;
                hx += dot(b.center, x) * scale + ox;
                hy += oy - dot(b.center, y) * scale;
                hn++;
            }
        }
        LAST_HEAD.set(hn > 0 ? new double[] {hx / hn, hy / hn} : null);

        int[] pix = new int[w * h];
        double[] zbuf = new double[w * h];
        Arrays.fill(zbuf, -Double.MAX_VALUE);
        // texels copied once (BufferedImage.getRGB per texel was most of the time)
        List<int[]> texels = new ArrayList<>();
        for (Part p : parts) texels.add(texels(p.texture()));

        for (int qi = 0; qi < quads.size(); qi++) {
            double[][] q = quads.get(qi);
            Box b = owners.get(qi);
            float[][] uv = b.uv[faceIdx.get(qi)];
            int[] tex = texels.get(b.texture);
            if (tex == null) continue;
            BufferedImage texImage = parts.get(b.texture).texture();
            int tw = texImage.getWidth(), th = texImage.getHeight();
            // Facing: from the box center out through the face center, not the winding (a negative stretch mirrors a
            // box and flips its winding; Gobliterator's crane boulder has stretch x -1). Quads (no thickness) are
            // drawn from both sides.
            double[] normal;
            if (b.quad) {
                normal = norm(cross(sub(q[2], q[0]), sub(q[1], q[0])));
                if (dot(normal, z) < 0) normal = new double[] {-normal[0], -normal[1], -normal[2]};
            } else {
                double[] fc = {(q[0][0] + q[3][0]) / 2, (q[0][1] + q[3][1]) / 2, (q[0][2] + q[3][2]) / 2};
                normal = norm(sub(fc, b.center));
                if (!b.doubleSided && dot(normal, z) <= 0) continue;
            }
            float light = shade ? light(normal) : 1f;
            double bias = b.quad ? QUAD_BIAS : 0;
            double[][] s = new double[4][];
            for (int k = 0; k < 4; k++) s[k] = new double[] {ox + dot(q[k], x) * scale, oy - dot(q[k], y) * scale, dot(q[k], z) + bias};
            triangle(s[0], s[2], s[1], uv[0], uv[2], uv[1], tex, tw, th, light, pix, zbuf, w, h);
            triangle(s[2], s[3], s[1], uv[2], uv[3], uv[1], tex, tw, th, light, pix, zbuf, w, h);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, pix, 0, w);
        return out;
    }

    /** Texels of each texture image, copied once (weak keys: an image no longer used lets its copy go). */
    private static final Map<BufferedImage, int[]> TEXELS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static int[] texels(BufferedImage t) {
        if (t == null) return null;
        return TEXELS.computeIfAbsent(t, k -> k.getRGB(0, 0, k.getWidth(), k.getHeight(), null, 0, k.getWidth()));
    }

    /** Depth bias of quads (model units, 1/16 block). */
    private static final double QUAD_BIAS = 0.05;

    /** Light per face direction in model space: top 1, sides 0.8-0.9, bottom 0.6 (Hytale-like flat shading). */
    private static float light(double[] n) {
        double v = 0.8 + 0.2 * n[1] + 0.06 * n[2] - 0.04 * n[0];
        return (float) Math.max(0.55, Math.min(1, v));
    }

    /** Boxes whose center is within 3x the 80th-percentile distance from the median center (outliers dropped). */
    private static boolean[] keepMainMass(List<double[]> centers) {
        int n = centers.size();
        boolean[] keep = new boolean[n];
        Arrays.fill(keep, true);
        if (n < 5) return keep;
        double[] med = new double[3];
        for (int a = 0; a < 3; a++) {
            double[] vals = new double[n];
            for (int i = 0; i < n; i++) vals[i] = centers.get(i)[a];
            Arrays.sort(vals);
            med[a] = vals[n / 2];
        }
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = Math.sqrt(dist2(centers.get(i), med));
        double[] sorted = d.clone();
        Arrays.sort(sorted);
        double limit = Math.max(1, sorted[(int) Math.floor((n - 1) * 0.8)]) * 3;
        for (int i = 0; i < n; i++) keep[i] = d[i] <= limit;
        return keep;
    }

    private static void triangle(double[] a, double[] b, double[] c, float[] ta, float[] tb, float[] tc, int[] tex, int tw,
                                 int th, float light, int[] pix, double[] zbuf, int w, int h) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        for (int py = y0; py <= y1; py++) {
            double cy = py + 0.5;
            for (int px = x0; px <= x1; px++) {
                double cx = px + 0.5;
                double w0 = ((b[0] - cx) * (c[1] - cy) - (b[1] - cy) * (c[0] - cx)) / area;
                double w1 = ((c[0] - cx) * (a[1] - cy) - (c[1] - cy) * (a[0] - cx)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                double depth = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = py * w + px;
                if (depth <= zbuf[idx]) continue;
                double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0];
                double v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
                int tx = Math.floorMod((int) Math.floor(u), tw), ty = Math.floorMod((int) Math.floor(v), th);
                int argb = tex[ty * tw + tx];
                if ((argb >>> 24) < 128) continue;
                zbuf[idx] = depth;
                int r = Math.min(255, Math.round(((argb >> 16) & 255) * light));
                int g = Math.min(255, Math.round(((argb >> 8) & 255) * light));
                int bl = Math.min(255, Math.round((argb & 255) * light));
                pix[idx] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }

    /** left (default), right, front, profileLeft, profileRight; null for an unknown word. */
    static double[] viewOf(String word) {
        return switch (word == null ? "left" : word.toLowerCase(java.util.Locale.ROOT)) {
            case "left" -> THREE_QUARTER;
            case "right" -> THREE_QUARTER_RIGHT;
            case "front" -> FRONT;
            case "profileleft", "sideleft" -> PROFILE_LEFT;
            case "profileright", "sideright" -> PROFILE_RIGHT;
            default -> null;
        };
    }

    // ---- small math ----

    private static double[] vec(JsonElement e, double fallback) {
        if (e == null || !e.isJsonObject()) return new double[] {fallback, fallback, fallback};
        JsonObject o = e.getAsJsonObject();
        return new double[] {num(o, "x", fallback), num(o, "y", fallback), num(o, "z", fallback)};
    }

    private static double[] vec2(JsonElement e) {
        if (e == null || !e.isJsonObject()) return new double[2];
        JsonObject o = e.getAsJsonObject();
        return new double[] {num(o, "x", 0), num(o, "y", 0)};
    }

    private static double[] quat(JsonElement e) {
        if (e == null || !e.isJsonObject()) return new double[] {0, 0, 0, 1};
        JsonObject o = e.getAsJsonObject();
        double[] q = {num(o, "x", 0), num(o, "y", 0), num(o, "z", 0), num(o, "w", 1)};
        double len = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len < 1e-9) return new double[] {0, 0, 0, 1};
        for (int i = 0; i < 4; i++) q[i] /= len;
        return q;
    }

    private static double num(JsonObject o, String k, double fallback) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : fallback;
    }

    private static double[] rotate(double[] q, double[] v) {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double tx = 2 * (qy * v[2] - qz * v[1]);
        double ty = 2 * (qz * v[0] - qx * v[2]);
        double tz = 2 * (qx * v[1] - qy * v[0]);
        return new double[] {
                v[0] + qw * tx + (qy * tz - qz * ty),
                v[1] + qw * ty + (qz * tx - qx * tz),
                v[2] + qw * tz + (qx * ty - qy * tx)};
    }

    private static void swap(double[] a) {
        double t = a[0];
        a[0] = a[1];
        a[1] = t;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l < 1e-12 ? new double[] {0, 0, 1} : new double[] {a[0] / l, a[1] / l, a[2] / l};
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Offline check: java ModelThumbnail out.png size [anim=path.blockyanim] [t=0.35] [view=left|right|front|profileLeft|profileRight]
     * model.blockymodel texture.png[@gradient.png] [attachment.blockymodel texture.png[@gradient.png]]...
     */
    /**
     * A greyscale texture tinted by a gradient strip (2026-10-16, player skins in the VS screen): the game's skin, hair
     * and clothes textures are grey and take their color from a GradientSet entry, a 256-wide strip
     * (Cosmetics/CharacterCreator/GradientSets.json, TintGradients/...): each grey pixel takes the strip's color at its
     * grey level (middle row); colored pixels (not grey) and alpha stay. ❓ Read from the data, not the client's shader:
     * the in-game tint may differ slightly.
     */
    static BufferedImage tint(BufferedImage grey, BufferedImage gradient) {
        if (grey == null || gradient == null) return grey;
        int gw = gradient.getWidth(), row = gradient.getHeight() / 2;
        int[] strip = new int[256];
        for (int i = 0; i < 256; i++) strip[i] = gradient.getRGB(Math.min(gw - 1, i * gw / 256), row) & 0xffffff;
        int w = grey.getWidth(), h = grey.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = grey.getRGB(x, y);
                int a = c >>> 24, r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                boolean isGrey = Math.abs(r - g) <= 6 && Math.abs(g - b) <= 6;
                out.setRGB(x, y, a == 0 || !isGrey ? c : a << 24 | strip[(r + g + b) / 3]);
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        List<Part> parts = new ArrayList<>();
        String animPath = null;
        double t = 0.35;
        double[] view = THREE_QUARTER;
        int first = 2;
        while (first < args.length && args[first].contains("=")) {
            String[] kv = args[first].split("=", 2);
            switch (kv[0]) {
                case "anim" -> animPath = kv[1];
                case "t" -> t = Double.parseDouble(kv[1]);
                case "view" -> view = viewOf(kv[1]);
                default -> throw new IllegalArgumentException(args[first]);
            }
            first++;
        }
        Pose pose = animPath == null ? null : new Pose(java.nio.file.Files.readString(java.nio.file.Path.of(animPath)), t);
        for (int i = first; i + 1 < args.length; i += 2) {
            // "texture.png@gradient.png" = a greyscale texture tinted by that gradient strip
            String[] tex = args[i + 1].split("@", 2);
            BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(tex[0]));
            if (tex.length > 1) img = tint(img, javax.imageio.ImageIO.read(new java.io.File(tex[1])));
            parts.add(new Part(java.nio.file.Files.readString(java.nio.file.Path.of(args[i])), img));
        }
        BufferedImage img = null;
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {   // the first runs warm the JIT up
            long t0 = System.nanoTime();
            img = render(parts, pose, Integer.parseInt(args[1]), view, true);
            best = Math.min(best, System.nanoTime() - t0);
        }
        if (img == null) throw new IllegalStateException("nothing visible");
        javax.imageio.ImageIO.write(img, "png", new java.io.File(args[0]));
        double[] head = LAST_HEAD.get();
        System.out.println(args[0] + " " + img.getWidth() + "x" + img.getHeight() + " in " + best / 1_000_000.0 + " ms"
                + (head != null ? " head=" + Math.round(head[0]) + "," + Math.round(head[1]) : ""));
    }
}
