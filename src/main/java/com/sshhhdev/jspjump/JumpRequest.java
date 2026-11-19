package com.sshhhdev.jspjump;

import java.util.List;

/**
 * JSON body sent by the bookmarklet (all fields optional).
 */
public final class JumpRequest {

    public Page page;
    public Target target;
    public Grid grid;
    /** Nearest ancestors that have an id, nearest first. */
    public List<Node> ancestors;
    /** Optional project name hint. */
    public String project;
    /** Search only, do not open anything (used to pick the right IDE when several are running). */
    public boolean dryRun;
    /** Diagnostics: override how the IDE window is raised (NUDGE, ALT, PLAIN). */
    public String raise;

    public static final class Page {
        public String url;
        public String path;
        public String title;
    }

    public static final class Target {
        public String tag;
        public String id;
        public String name;
        public String type;
        public String value;
        public String text;
        public String className;
    }

    public static final class Grid {
        /** DOM id of the nearest element with an id around the grid (without '#'). */
        public String gridId;
        /** Which detector found it: AUIGrid, jqGrid, DataTables, col-id, data-field, SlickGrid, table ... */
        public String kind;
        /** Column key as written in the column definition (field / dataField / name / data ...). */
        public String dataField;
        public String headerText;
        /** "header" or "body". */
        public String part;
    }

    public static final class Node {
        public String tag;
        public String id;
    }
}
