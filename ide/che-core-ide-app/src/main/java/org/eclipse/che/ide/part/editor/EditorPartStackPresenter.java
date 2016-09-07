/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.part.editor;

import com.google.common.base.Predicate;
import com.google.gwt.core.client.Scheduler;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.ide.api.action.Action;
import org.eclipse.che.ide.api.action.ActionEvent;
import org.eclipse.che.ide.api.action.ActionManager;
import org.eclipse.che.ide.api.action.Presentation;
import org.eclipse.che.ide.api.constraints.Constraints;
import org.eclipse.che.ide.api.constraints.Direction;
import org.eclipse.che.ide.api.editor.AbstractEditorPresenter;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.EditorWithErrors;
import org.eclipse.che.ide.api.editor.EditorWithErrors.EditorState;
import org.eclipse.che.ide.api.event.FileEvent;
import org.eclipse.che.ide.api.parts.EditorPartStack;
import org.eclipse.che.ide.api.parts.EditorTab;
import org.eclipse.che.ide.api.parts.PartPresenter;
import org.eclipse.che.ide.api.parts.PartStackView.TabItem;
import org.eclipse.che.ide.api.parts.PropertyListener;
import org.eclipse.che.ide.api.resources.ResourceChangedEvent;
import org.eclipse.che.ide.api.resources.ResourceChangedEvent.ResourceChangedHandler;
import org.eclipse.che.ide.api.resources.ResourceDelta;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.part.PartStackPresenter;
import org.eclipse.che.ide.part.PartsComparator;
import org.eclipse.che.ide.part.editor.actions.CloseAllTabsPaneAction;
import org.eclipse.che.ide.part.editor.actions.ClosePaneAction;
import org.eclipse.che.ide.part.editor.event.CloseNonPinnedEditorsEvent;
import org.eclipse.che.ide.part.editor.event.CloseNonPinnedEditorsEvent.CloseNonPinnedEditorsHandler;
import org.eclipse.che.ide.part.editor.event.SplitEmptyPaneEvent;
import org.eclipse.che.ide.part.widgets.TabItemFactory;
import org.eclipse.che.ide.part.widgets.panemenu.EditorPaneMenu;
import org.eclipse.che.ide.part.widgets.panemenu.EditorPaneMenuItem;
import org.eclipse.che.ide.part.widgets.panemenu.PaneMenuActionItemWidget;
import org.eclipse.che.ide.part.widgets.panemenu.PaneMenuTabItemWidget;
import org.eclipse.che.ide.resource.Path;
import org.eclipse.che.ide.ui.toolbar.PresentationFactory;
import org.eclipse.che.ide.util.loging.Log;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.filter;
import static org.eclipse.che.ide.actions.EditorActions.SPLIT_HORIZONTALLY;
import static org.eclipse.che.ide.actions.EditorActions.SPLIT_VERTICALLY;
import static org.eclipse.che.ide.api.editor.EditorWithErrors.EditorState.ERROR;
import static org.eclipse.che.ide.api.editor.EditorWithErrors.EditorState.WARNING;
import static org.eclipse.che.ide.api.resources.ResourceDelta.REMOVED;
import static org.eclipse.che.ide.part.editor.actions.EditorAbstractAction.CURRENT_FILE_PROP;
import static org.eclipse.che.ide.part.editor.actions.EditorAbstractAction.CURRENT_TAB_PROP;

/**
 * EditorPartStackPresenter is a special PartStackPresenter that is shared among all
 * Perspectives and used to display Editors.
 *
 * @author Nikolay Zamosenchuk
 * @author Stéphane Daviet
 * @author Dmitry Shnurenko
 * @author Vlad Zhukovskyi
 * @author Roman Nikitenko
 */
public class EditorPartStackPresenter extends PartStackPresenter implements EditorPartStack,
                                                                            EditorTab.ActionDelegate,
                                                                            EditorPaneMenu.ActionDelegate,
                                                                            CloseNonPinnedEditorsHandler,
                                                                            ResourceChangedHandler {
    private final PresentationFactory presentationFactory;
    private final EventBus            eventBus;
    private final EditorPaneMenu      editorPaneMenu;
    private final ActionManager       actionManager;
    private       ClosePaneAction     closePaneAction;
    private CloseAllTabsPaneAction closeAllTabsPaneAction;
    private final Map<EditorPaneMenuItem, TabItem> items;

    //this list need to save order of added parts
    private final LinkedList<EditorPartPresenter> partsOrder;
    private final LinkedList<EditorPartPresenter> closedParts;

    private HandlerRegistration closeNonPinnedEditorsHandler;
    private HandlerRegistration resourceChangeHandler;

    @Inject
    public EditorPartStackPresenter(EditorPartStackView view,
                                    PartsComparator partsComparator,
                                    PresentationFactory presentationFactory,
                                    EventBus eventBus,
                                    TabItemFactory tabItemFactory,
                                    PartStackEventHandler partStackEventHandler,
                                    EditorPaneMenu editorPaneMenu,
                                    ActionManager actionManager,
                                    ClosePaneAction closePaneAction,
                                    CloseAllTabsPaneAction closeAllTabsPaneAction) {
        //noinspection ConstantConditions
        super(eventBus, partStackEventHandler, tabItemFactory, partsComparator, view, null);
        this.eventBus = eventBus;
        this.presentationFactory = presentationFactory;

        this.editorPaneMenu = editorPaneMenu;
        this.actionManager = actionManager;
        this.closePaneAction = closePaneAction;
        this.closeAllTabsPaneAction = closeAllTabsPaneAction;
        this.editorPaneMenu.setDelegate(this);

        this.view.setDelegate(this);
        this.items = new HashMap<>();
        this.partsOrder = new LinkedList<>();
        this.closedParts = new LinkedList<>();

        closeNonPinnedEditorsHandler = eventBus.addHandler(CloseNonPinnedEditorsEvent.getType(), this);
        resourceChangeHandler = eventBus.addHandler(ResourceChangedEvent.getType(), this);

        initializePaneMenu();
        view.addPaneMenuButton(editorPaneMenu);
    }

    private void initializePaneMenu() {
        editorPaneMenu.addItem(new PaneMenuActionItemWidget(closePaneAction));
        editorPaneMenu.addItem(new PaneMenuActionItemWidget(closeAllTabsPaneAction), true);

        Action splitHorizontallyAction = actionManager.getAction(SPLIT_HORIZONTALLY);
        editorPaneMenu.addItem(new PaneMenuActionItemWidget(splitHorizontallyAction));

        Action splitVerticallyAction = actionManager.getAction(SPLIT_VERTICALLY);
        editorPaneMenu.addItem(new PaneMenuActionItemWidget(splitVerticallyAction));
    }

    @Nullable
    private EditorPaneMenuItem getListItemByTab(@NotNull TabItem tabItem) {
        for (Entry<EditorPaneMenuItem, TabItem> entry : items.entrySet()) {
            if (tabItem.equals(entry.getValue())) {
                return entry.getKey();
            }
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void setFocus(boolean focused) {
        view.setFocus(focused);
    }

    /** {@inheritDoc} */
    @Override
    public void addPart(@NotNull PartPresenter part, Constraints constraint) {
        addPart(part);
    }

    /** {@inheritDoc} */
    @Override
    public void addPart(@NotNull PartPresenter part) {
        checkArgument(part instanceof AbstractEditorPresenter, "Can not add part " + part.getTitle() + " to editor part stack");

        EditorPartPresenter editorPart = (AbstractEditorPresenter)part;
        if (containsPart(editorPart)) {
            setActivePart(editorPart);
            Log.error(getClass(), "setActivePart RETURN");
            return;
        }

        VirtualFile file = editorPart.getEditorInput().getFile();
        checkArgument(file != null, "File doesn't provided");

        updateListClosedParts(file);

        editorPart.addPropertyListener(propertyListener);

        final EditorTab editorTab = tabItemFactory.createEditorPartButton(editorPart);

        editorPart.addPropertyListener(new PropertyListener() {
            @Override
            public void propertyChanged(PartPresenter source, int propId) {
                if (propId == EditorPartPresenter.PROP_INPUT && source instanceof EditorPartPresenter) {
                    editorTab.setReadOnlyMark(((EditorPartPresenter)source).getEditorInput().getFile().isReadOnly());
                }
            }
        });

        editorTab.setDelegate(this);

        parts.put(editorTab, editorPart);
        partsOrder.add(editorPart);

        view.addTab(editorTab, editorPart);

        TabItem tabItem = getTabByPart(editorPart);

        if (tabItem != null) {
            EditorPaneMenuItem item = new PaneMenuTabItemWidget(tabItem);
            editorPaneMenu.addItem(item);
            items.put(item, tabItem);
        }

        if (editorPart instanceof EditorWithErrors) {
            final EditorWithErrors presenter = ((EditorWithErrors)editorPart);

            editorPart.addPropertyListener(new PropertyListener() {
                @Override
                public void propertyChanged(PartPresenter source, int propId) {
                    EditorState editorState = presenter.getErrorState();

                    editorTab.setErrorMark(ERROR.equals(editorState));
                    editorTab.setWarningMark(WARNING.equals(editorState));
                }
            });
        }
        view.selectTab(editorPart);
    }

    private void updateListClosedParts(VirtualFile file) {
        if (closedParts.isEmpty()) {
            return;
        }

        for (EditorPartPresenter closedEditorPart : closedParts) {
            Path path = closedEditorPart.getEditorInput().getFile().getLocation();
            if (path.equals(file.getLocation())) {
                closedParts.remove(closedEditorPart);
                return;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public PartPresenter getActivePart() {
        return activePart;
    }

    /** {@inheritDoc} */
    @Override
    public void setActivePart(@NotNull PartPresenter part) {
        activePart = part;
        view.selectTab(part);
    }

    /** {@inheritDoc} */
    @Override
    public void onTabClicked(@NotNull TabItem tab) {
        activePart = parts.get(tab);
        view.selectTab(parts.get(tab));
    }

    /** {@inheritDoc} */
    @Override
    public void onItemClicked(@NotNull EditorPaneMenuItem item) {
        Object data = item.getData();
        if (data instanceof TabItem) {
            TabItem tabItem = (TabItem)data;
            activePart = parts.get(tabItem);
            view.selectTab(parts.get(tabItem));
        } else if (data instanceof Action) {
            onActionClicked((Action)data);
        }
    }

    @Override
    public void onItemClose(@NotNull EditorPaneMenuItem item) {
        Object data = item.getData();
        if (data instanceof EditorTab) {
            EditorTab editorTab = (EditorTab)data;
            eventBus.fireEvent(FileEvent.createCloseFileEvent(editorTab));
        }
    }

    private void onActionClicked(Action action) {
        final Presentation presentation = presentationFactory.getPresentation(action);
        PartPresenter activePart = getActivePart();
        Log.error(getClass(), "=== on action clicked active part " + activePart);
        TabItem tab = getTabByPart(activePart);
        if (tab == null) {
            eventBus.fireEvent(new SplitEmptyPaneEvent(Direction.HORIZONTALLY, this));
            return;
        }
        Log.error(getClass(), "=== on action clicked tab " + tab);
        VirtualFile virtualFile = ((EditorTab)tab).getFile();
        Log.error(getClass(), "=== on action clicked file " + virtualFile);
        //pass into action file property and editor tab
        presentation.putClientProperty(CURRENT_TAB_PROP, tab);
        presentation.putClientProperty(CURRENT_FILE_PROP, virtualFile);
        action.actionPerformed(new ActionEvent(presentation, actionManager, null));
    }

    /** {@inheritDoc} */
    @Override
    public void removePart(PartPresenter part) {
        super.removePart(part);
        if (!(part instanceof EditorPartPresenter)) {
            return;
        }

        partsOrder.remove(part);
        activePart = partsOrder.isEmpty() ? null : partsOrder.getLast();

        if (activePart != null) {
            onRequestFocus();
        }

        if (parts.isEmpty()) {
            resourceChangeHandler.removeHandler();
            closeNonPinnedEditorsHandler.removeHandler();
        }
    }

    @Override
    public void openPreviousActivePart() {
        if (activePart != null) {
            view.selectTab(activePart);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onTabClose(@NotNull TabItem tab) {
        EditorPaneMenuItem editorPaneMenuItem = getListItemByTab(tab);
        editorPaneMenu.removeItem(editorPaneMenuItem);
        items.remove(editorPaneMenuItem);

        EditorPartPresenter part = ((EditorTab)tab).getRelativeEditorPart();
        closedParts.add(part);
    }

    /** {@inheritDoc} */
    @Override
    public void onCloseNonPinnedEditors(CloseNonPinnedEditorsEvent event) {
        EditorPartPresenter editorPart = event.getEditorTab().getRelativeEditorPart();
        if (!containsPart(editorPart)) {
            return;
        }

        Iterable<TabItem> nonPinned = filter(parts.keySet(), new Predicate<TabItem>() {
            @Override
            public boolean apply(@Nullable TabItem input) {
                return input instanceof EditorTab && !((EditorTab)input).isPinned();
            }
        });

        for (final TabItem tabItem : nonPinned) {
            Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
                @Override
                public void execute() {
                    eventBus.fireEvent(FileEvent.createCloseFileEvent((EditorTab)tabItem));
                }
            });
        }
    }

    @Override
    public EditorPartPresenter getPartByTabId(@NotNull String tabId) {
        for (TabItem tab : parts.keySet()) {
            EditorTab currentTab = (EditorTab)tab;
            if (currentTab.getId().equals(tabId)) {
                return (EditorPartPresenter)parts.get(currentTab);
            }
        }
        return null;
    }

    @Nullable
    public EditorTab getTabByPart(@NotNull EditorPartPresenter part) {
        for (Map.Entry<TabItem, PartPresenter> entry : parts.entrySet()) {
            PartPresenter currentPart = entry.getValue();
            if (part.equals(currentPart)) {
                return (EditorTab)entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    public PartPresenter getPartByPath(Path path) {
        for (TabItem tab : parts.keySet()) {
            EditorTab editorTab = (EditorTab)tab;
            Path currentPath = editorTab.getFile().getLocation();

            if (currentPath.equals(path)) {
                return parts.get(tab);
            }
        }
        return null;
    }

    @Override
    public EditorPartPresenter getNextFor(EditorPartPresenter editorPart) {
        int indexForNext = partsOrder.indexOf(editorPart) + 1;
        return indexForNext >= partsOrder.size() ? partsOrder.getFirst() : partsOrder.get(indexForNext);
    }

    @Override
    public EditorPartPresenter getPreviousFor(EditorPartPresenter editorPart) {
        int indexForNext = partsOrder.indexOf(editorPart) - 1;
        return indexForNext < 0 ? partsOrder.getLast() : partsOrder.get(indexForNext);
    }

    @Nullable
    @Override
    public EditorPartPresenter getLastClosed() {
        if (closedParts.isEmpty()) {
            return null;
        }
        return closedParts.getLast();
    }

    @Override
    public void onResourceChanged(ResourceChangedEvent event) {
        final ResourceDelta delta = event.getDelta();
        if (delta.getKind() != REMOVED) {
            return;
        }

        for (EditorPartPresenter editorPart : closedParts) {
            Path resourcePath = delta.getResource().getLocation();
            Path editorPath = editorPart.getEditorInput().getFile().getLocation();

            if (editorPath.equals(resourcePath)) {
                closedParts.remove(editorPart);
                return;
            }
        }
    }
}
