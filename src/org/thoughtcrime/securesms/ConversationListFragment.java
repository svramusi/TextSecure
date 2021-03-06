/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockListFragment;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.SearchView;

import org.thoughtcrime.securesms.components.DefaultSmsReminder;
import org.thoughtcrime.securesms.components.PushRegistrationReminder;
import org.thoughtcrime.securesms.components.ReminderView;
import org.thoughtcrime.securesms.components.SystemSmsImportReminder;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.loaders.ConversationListLoader;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.util.Dialogs;
import org.whispersystems.textsecure.crypto.MasterSecret;

import java.util.Set;


public class ConversationListFragment extends SherlockListFragment
  implements LoaderManager.LoaderCallbacks<Cursor>, ActionMode.Callback
{

  private ConversationSelectedListener listener;
  private MasterSecret                 masterSecret;
  private ActionMode                   actionMode;
  private ReminderView                 reminderView;
  private String                       queryFilter  = "";

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);
    reminderView = new ReminderView(getActivity());
    return view;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    getListView().setAdapter(null);
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    setHasOptionsMenu(true);
    getListView().setAdapter(null);
    getListView().addHeaderView(reminderView);
    initializeListAdapter();
    initializeBatchListener();

    getLoaderManager().initLoader(0, null, this);
  }

  @Override
  public void onResume() {
    super.onResume();

    initializeReminders();
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    this.listener = (ConversationSelectedListener)activity;
  }

  @Override
  public void onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getSherlockActivity().getSupportMenuInflater();

    if (this.masterSecret != null) {
      inflater.inflate(R.menu.conversation_list, menu);
      initializeSearch((SearchView)menu.findItem(R.id.menu_search).getActionView());
    } else {
      inflater.inflate(R.menu.conversation_list_empty, menu);
    }

    super.onPrepareOptionsMenu(menu);
  }

  @Override
  public void onListItemClick(ListView l, View v, int position, long id) {
    if (v instanceof ConversationListItem) {
      ConversationListItem headerView = (ConversationListItem) v;
      if (actionMode == null) {
        handleCreateConversation(headerView.getThreadId(), headerView.getRecipients(),
                                 headerView.getDistributionType());
      } else {
        ConversationListAdapter adapter = (ConversationListAdapter)getListAdapter();
        adapter.toggleThreadInBatchSet(headerView.getThreadId());

        if (adapter.getBatchSelections().size() == 0) {
          actionMode.finish();
        } else {
          actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
                                           adapter.getBatchSelections().size()));
        }

        adapter.notifyDataSetChanged();
      }
    }
  }

  public void setMasterSecret(MasterSecret masterSecret) {
    if (this.masterSecret != masterSecret) {
      this.masterSecret = masterSecret;
      initializeListAdapter();
    }
  }

  private void setQueryFilter(String query) {
    this.queryFilter = query;
    getLoaderManager().restartLoader(0, null, this);
  }

  private void initializeSearch(SearchView searchView) {
    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
      @Override
      public boolean onQueryTextSubmit(String query) {
        if (isAdded()) {
          setQueryFilter(query);
          return true;
        }
        return false;
      }

      @Override
      public boolean onQueryTextChange(String newText) {
        return onQueryTextSubmit(newText);
      }
    });
  }

  private void initializeBatchListener() {
    getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
      @Override
      public boolean onItemLongClick(AdapterView<?> arg0, View v, int position, long id) {
        ConversationListAdapter adapter = (ConversationListAdapter)getListAdapter();
        actionMode = getSherlockActivity().startActionMode(ConversationListFragment.this);

        adapter.initializeBatchMode(true);
        adapter.toggleThreadInBatchSet(((ConversationListItem) v).getThreadId());
        adapter.notifyDataSetChanged();

        return true;
      }
    });
  }

  private void initializeReminders() {
    if (DefaultSmsReminder.isEligible(getActivity())) {
      reminderView.showReminder(new DefaultSmsReminder(getActivity()));
    } else if (SystemSmsImportReminder.isEligible(getActivity())) {
      reminderView.showReminder(new SystemSmsImportReminder(getActivity(), masterSecret));
    } else if (PushRegistrationReminder.isEligible(getActivity())) {
      reminderView.showReminder(new PushRegistrationReminder(getActivity(), masterSecret));
    } else {
      reminderView.hide();
    }
  }

  private void initializeListAdapter() {
    this.setListAdapter(new ConversationListAdapter(getActivity(), null, masterSecret));
    getListView().setRecyclerListener((ConversationListAdapter)getListAdapter());
    getLoaderManager().restartLoader(0, null, this);
  }

  private void handleDeleteAllSelected() {
    AlertDialog.Builder alert = new AlertDialog.Builder(getActivity());
    alert.setIcon(Dialogs.resolveIcon(getActivity(), R.attr.dialog_alert_icon));
    alert.setTitle(R.string.ConversationListFragment_delete_threads_question);
    alert.setMessage(R.string.ConversationListFragment_are_you_sure_you_wish_to_delete_all_selected_conversation_threads);
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        final Set<Long> selectedConversations = ((ConversationListAdapter)getListAdapter())
            .getBatchSelections();

        if (!selectedConversations.isEmpty()) {
          new AsyncTask<Void, Void, Void>() {
            private ProgressDialog dialog;

            @Override
            protected void onPreExecute() {
              dialog = ProgressDialog.show(getActivity(),
                                           getSherlockActivity().getString(R.string.ConversationListFragment_deleting),
                                           getSherlockActivity().getString(R.string.ConversationListFragment_deleting_selected_threads),
                                           true, false);
            }

            @Override
            protected Void doInBackground(Void... params) {
              DatabaseFactory.getThreadDatabase(getActivity()).deleteConversations(selectedConversations);
              MessageNotifier.updateNotification(getActivity(), masterSecret);
              return null;
            }

            @Override
            protected void onPostExecute(Void result) {
              dialog.dismiss();
              if (actionMode != null) {
                actionMode.finish();
                actionMode = null;
              }
            }
          }.execute();
        }
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handleSelectAllThreads() {
    ((ConversationListAdapter)this.getListAdapter()).selectAllThreads();
    actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
                           ((ConversationListAdapter)this.getListAdapter()).getBatchSelections().size()));
  }

  private void handleUnselectAllThreads() {
    ((ConversationListAdapter)this.getListAdapter()).selectAllThreads();
    actionMode.setSubtitle(getString(R.string.conversation_fragment_cab__batch_selection_amount,
                           ((ConversationListAdapter)this.getListAdapter()).getBatchSelections().size()));
  }

  private void handleCreateConversation(long threadId, Recipients recipients, int distributionType) {
    listener.onCreateConversation(threadId, recipients, distributionType);
  }

  @Override
  public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
    return new ConversationListLoader(getActivity(), queryFilter);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> arg0, Cursor cursor) {
    ((CursorAdapter)getListAdapter()).changeCursor(cursor);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> arg0) {
    ((CursorAdapter)getListAdapter()).changeCursor(null);
  }

  public interface ConversationSelectedListener {
    public void onCreateConversation(long threadId, Recipients recipients, int distributionType);
}

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    MenuInflater inflater = getSherlockActivity().getSupportMenuInflater();
    inflater.inflate(R.menu.conversation_list_batch, menu);

    mode.setTitle(R.string.conversation_fragment_cab__batch_selection_mode);
    mode.setSubtitle(null);

    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    switch (item.getItemId()) {
    case R.id.menu_select_all:      handleSelectAllThreads(); return true;
    case R.id.menu_delete_selected: handleDeleteAllSelected(); return true;
    }

    return false;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    ((ConversationListAdapter)getListAdapter()).initializeBatchMode(false);
    actionMode = null;
  }

}


