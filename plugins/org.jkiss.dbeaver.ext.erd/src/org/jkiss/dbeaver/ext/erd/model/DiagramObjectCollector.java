/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ext.erd.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.ext.erd.ERDActivator;
import org.jkiss.dbeaver.ext.erd.ERDConstants;
import org.jkiss.dbeaver.model.DBPNamedObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Table collector
 */
public class DiagramObjectCollector {

    private static final Log log = Log.getLog(DiagramObjectCollector.class);

    private final EntityDiagram diagram;
    private final List<ERDEntity> erdEntities = new ArrayList<>();
    private final Map<DBSEntity, ERDEntity> tableMap = new HashMap<>();

    public DiagramObjectCollector(EntityDiagram diagram)
    {
        this.diagram = diagram;
        this.tableMap.putAll(diagram.getTableMap());
    }

    public static Collection<DBSEntity> collectTables(
        DBRProgressMonitor monitor,
        Collection<? extends DBSObject> roots)
        throws DBException
    {
        Set<DBSEntity> tables = new LinkedHashSet<>();
        collectTables(monitor, roots, tables);
        return tables;
    }

    private static void collectTables(
        DBRProgressMonitor monitor,
        Collection<? extends DBSObject> roots,
        Set<DBSEntity> tables)
        throws DBException
    {
        for (DBSObject root : roots) {
            if (monitor.isCanceled()) {
                break;
            }
            if (root instanceof DBSFolder) {
                collectTables(monitor, ((DBSFolder) root).getChildrenObjects(monitor), tables);
            } else if (root instanceof DBSEntity) {
                tables.add((DBSEntity) root);
            }
            if (root instanceof DBSObjectContainer) {
                collectTables(monitor, (DBSObjectContainer) root, tables);
            }
        }
    }

    private static void collectTables(
        DBRProgressMonitor monitor,
        DBSObjectContainer container,
        Set<DBSEntity> tables)
        throws DBException
    {
        if (monitor.isCanceled()) {
            return;
        }
        container.cacheStructure(monitor, DBSObjectContainer.STRUCT_ALL);
        final Collection<? extends DBSObject> children = container.getChildren(monitor);
        if (!CommonUtils.isEmpty(children)) {
            Class<? extends DBSObject> childType = container.getChildType(monitor);
            DBSObjectFilter objectFilter = container.getDataSource().getContainer().getObjectFilter(childType, container, true);
            for (DBSObject entity : children) {
                if (monitor.isCanceled()) {
                    break;
                }
                if (objectFilter != null && !objectFilter.matches(entity.getName())) {
                    continue;
                }
                if (entity instanceof DBSEntity) {
                    tables.add((DBSEntity) entity);
                } else if (entity instanceof DBSObjectContainer) {
                    collectTables(monitor, (DBSObjectContainer) entity, tables);
                }
            }
        }
    }

    public void generateDiagramObjects(
        DBRProgressMonitor monitor,
        Collection<? extends DBSObject> roots)
        throws DBException
    {
        boolean showViews = ERDActivator.getDefault().getPreferenceStore().getBoolean(ERDConstants.PREF_DIAGRAM_SHOW_VIEWS);
        Collection<DBSEntity> tables = collectTables(monitor, roots);
        for (DBSEntity table : tables) {
            if (DBUtils.isHiddenObject(table)) {
                // Skip hidden tables
                continue;
            }
            if (!showViews && table instanceof DBSTable && ((DBSTable) table).isView()) {
                // Skip views
                continue;
            }
            addDiagramEntity(monitor, table);
        }

        // Add new relations
        for (ERDEntity erdEntity : erdEntities) {
            erdEntity.addRelations(monitor, tableMap, false);
        }
    }

    private void addDiagramEntity(DBRProgressMonitor monitor, DBSEntity table)
    {
        if (diagram.containsTable(table)) {
            // Avoid duplicates
            return;
        }
        ERDEntity erdEntity = ERDEntity.fromObject(monitor, diagram, table);
        if (erdEntity != null) {
            erdEntities.add(erdEntity);
            tableMap.put(table, erdEntity);
        }
    }

    public List<ERDEntity> getDiagramEntities()
    {
        return erdEntities;
    }

    public static List<ERDEntity> generateEntityList(final EntityDiagram diagram, Collection<DBPNamedObject> objects)
    {
        final List<DBSObject> roots = new ArrayList<>();
        for (DBPNamedObject object : objects) {
            if (object instanceof DBSObject) {
                roots.add((DBSObject) object);
            }
        }

        final List<ERDEntity> entities = new ArrayList<>();

        try {
            DBeaverUI.runInProgressService(new DBRRunnableWithProgress() {
                @Override
                public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
                {
                    DiagramObjectCollector collector = new DiagramObjectCollector(diagram);
                    try {
                        collector.generateDiagramObjects(monitor, roots);
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    }
                    entities.addAll(collector.getDiagramEntities());
                }
            });
        } catch (InvocationTargetException e) {
            log.error(e.getTargetException());
        } catch (InterruptedException e) {
            // interrupted
        }
        return entities;
    }

}
