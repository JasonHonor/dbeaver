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
package org.jkiss.dbeaver.ui.data.managers;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.ui.IEditorPart;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.CoreMessages;
import org.jkiss.dbeaver.model.DBPPropertyManager;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentCached;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.data.IStreamValueManager;
import org.jkiss.dbeaver.ui.data.IValueController;
import org.jkiss.dbeaver.ui.data.IValueEditor;
import org.jkiss.dbeaver.ui.data.editors.ContentInlineEditor;
import org.jkiss.dbeaver.ui.data.editors.ContentPanelEditor;
import org.jkiss.dbeaver.ui.data.editors.XMLPanelEditor;
import org.jkiss.dbeaver.ui.data.registry.StreamValueManagerDescriptor;
import org.jkiss.dbeaver.ui.data.registry.ValueManagerRegistry;
import org.jkiss.dbeaver.ui.dialogs.DialogUtils;
import org.jkiss.dbeaver.ui.dialogs.data.TextViewDialog;
import org.jkiss.dbeaver.ui.editors.content.ContentEditor;
import org.jkiss.dbeaver.utils.ContentUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDBC Content value handler.
 * Handle LOBs, LONGs and BINARY types.
 *
 * @author Serge Rider
 */
public class ContentValueManager extends BaseValueManager {

    private static final Log log = Log.getLog(ContentValueManager.class);

    public static final String PROP_CATEGORY_CONTENT = "CONTENT";

    public static void contributeContentActions(@NotNull IContributionManager manager, @NotNull final IValueController controller)
        throws DBCException
    {
        if (controller.getValue() instanceof DBDContent && !((DBDContent)controller.getValue()).isNull()) {
            manager.add(new Action(CoreMessages.model_jdbc_save_to_file_, DBeaverIcons.getImageDescriptor(UIIcon.SAVE_AS)) {
                @Override
                public void run() {
                    DialogUtils.saveToFile(controller);
                }
            });
        }
        manager.add(new Action(CoreMessages.model_jdbc_load_from_file_, DBeaverIcons.getImageDescriptor(UIIcon.LOAD)) {
            @Override
            public void run() {
                DialogUtils.loadFromFile(controller);
            }
        });
        manager.add(new Separator());
    }

    public static IValueEditor openContentEditor(@NotNull IValueController controller)
    {
        Object value = controller.getValue();
        IValueController.EditType binaryEditType = IValueController.EditType.valueOf(
            controller.getExecutionContext().getDataSource().getContainer().getPreferenceStore().getString(DBeaverPreferences.RESULT_SET_BINARY_EDITOR_TYPE));
        if (binaryEditType != IValueController.EditType.EDITOR && value instanceof DBDContentCached) {
            // Use string editor for cached content
            return new TextViewDialog(controller);
        } else if (value instanceof DBDContent) {
            DBDContent content = (DBDContent)value;
            Map<StreamValueManagerDescriptor, IStreamValueManager.MatchType> streamManagers =
                ValueManagerRegistry.getInstance().getApplicableStreamManagers(VoidProgressMonitor.INSTANCE, controller.getValueType(), content);
            //boolean isText = ContentUtils.isTextContent(content);
            List<IEditorPart> parts = new ArrayList<>();
            IEditorPart defaultPart = null;
            IStreamValueManager.MatchType defaultMatch = null;
            for (Map.Entry<StreamValueManagerDescriptor, IStreamValueManager.MatchType> entry : streamManagers.entrySet()) {
                IStreamValueManager streamValueManager = entry.getKey().getInstance();
                try {
                    IEditorPart editorPart = streamValueManager.createEditorPart(controller);
                    IStreamValueManager.MatchType matchType = entry.getValue();
                    if (defaultPart == null) {
                        defaultPart = editorPart;
                        defaultMatch = matchType;
                    } else {
                        boolean setDefault = false;
                        switch (matchType) {
                            case EXCLUSIVE:
                            case PRIMARY:
                                setDefault = true;
                                break;
                            case DEFAULT:
                                setDefault = (defaultMatch == IStreamValueManager.MatchType.APPLIES);
                                break;
                            default:
                                break;
                        }
                        if (setDefault) {
                            defaultPart = editorPart;
                            defaultMatch = matchType;
                        }
                    }
                    parts.add(editorPart);
                } catch (DBException e) {
                    log.error(e);
                }
            }
/*
            if (isText) {
                parts.add(new TextEditorPart());
                if (ContentUtils.isXML(content)) {
                    parts.add(new ContentXMLEditorPart());
                }
            } else {
                parts.add(new BinaryEditorPart());
                parts.add(new TextEditorPart());
                parts.add(new ImageEditorPart());
            }
*/
            return ContentEditor.openEditor(
                controller,
                parts.toArray(new IEditorPart[parts.size()]),
                defaultPart);
        } else {
            controller.showMessage(CoreMessages.model_jdbc_unsupported_content_value_type_, true);
            return null;
        }
    }

    @Override
    public void contributeActions(@NotNull IContributionManager manager, @NotNull final IValueController controller, @Nullable IValueEditor activeEditor)
        throws DBCException
    {
        super.contributeActions(manager, controller, activeEditor);
        contributeContentActions(manager, controller);
    }

    @Override
    public void contributeProperties(@NotNull DBPPropertyManager propertySource, @NotNull IValueController controller)
    {
        super.contributeProperties(propertySource, controller);
        try {
            Object value = controller.getValue();
            if (value instanceof DBDContent) {
                propertySource.addProperty(
                    PROP_CATEGORY_CONTENT,
                    "content_type", //$NON-NLS-1$
                    CoreMessages.model_jdbc_content_type,
                    ((DBDContent)value).getContentType());
                final long contentLength = ((DBDContent) value).getContentLength();
                if (contentLength >= 0) {
                    propertySource.addProperty(
                        PROP_CATEGORY_CONTENT,
                        "content_length", //$NON-NLS-1$
                        CoreMessages.model_jdbc_content_length,
                        contentLength);
                }
            }
        }
        catch (Exception e) {
            log.warn("Can't extract CONTENT value information", e); //$NON-NLS-1$
        }
    }

    @NotNull
    @Override
    public IValueController.EditType[] getSupportedEditTypes() {
        return new IValueController.EditType[] {IValueController.EditType.PANEL, IValueController.EditType.EDITOR};
    }

    @Override
    public IValueEditor createEditor(@NotNull final IValueController controller)
        throws DBException
    {
        switch (controller.getEditType()) {
            case INLINE:
                // Open inline/panel editor
                if (controller.getValue() instanceof DBDContentCached &&
                    ContentUtils.isTextValue(((DBDContentCached)controller.getValue()).getCachedValue()))
            {
                    return new ContentInlineEditor(controller);
                } else {
                    return null;
                }
            case EDITOR:
                return openContentEditor(controller);
            case PANEL:
                Object value = controller.getValue();
                if (value instanceof DBDContent && ContentUtils.isXML((DBDContent) value)) {
                    return new XMLPanelEditor(controller);
                } else {
                    return new ContentPanelEditor(controller);
                }
            default:
                return null;
        }
    }

}
