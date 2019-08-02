package com.fenritz.safecam;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;

import com.fenritz.safecam.Auth.LoginManager;
import com.fenritz.safecam.Crypto.CryptoException;
import com.fenritz.safecam.Db.StingleDbContract;
import com.fenritz.safecam.Db.StingleDbFile;
import com.fenritz.safecam.Db.StingleDbHelper;
import com.fenritz.safecam.Gallery.AutoFitGridLayoutManager;
import com.fenritz.safecam.Gallery.GalleryAdapterPisasso;
import com.fenritz.safecam.Gallery.HidingScrollListener;
import com.fenritz.safecam.Sync.FileManager;
import com.fenritz.safecam.Sync.SyncManager;
import com.fenritz.safecam.Sync.SyncService;
import com.fenritz.safecam.Util.Helpers;
import com.fenritz.safecam.Gallery.DragSelectRecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MenuInflater;
import android.view.View;

import androidx.appcompat.view.ActionMode;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GravityCompat;
import androidx.appcompat.app.ActionBarDrawerToggle;

import android.view.MenuItem;

import com.google.android.material.navigation.NavigationView;

import androidx.drawerlayout.widget.DrawerLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.view.Menu;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.sanselan.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class GalleryActivity extends AppCompatActivity
		implements NavigationView.OnNavigationItemSelectedListener, GalleryAdapterPisasso.Listener {

	protected DragSelectRecyclerView recyclerView;
	protected GalleryAdapterPisasso adapter;
	protected AutoFitGridLayoutManager layoutManager;
	protected ActionMode actionMode;
	protected Toolbar toolbar;
	protected int currentFolder = SyncManager.FOLDER_MAIN;
	protected ViewGroup syncBar;
	protected ProgressBar syncProgress;
	protected ProgressBar refreshCProgress;
	protected ImageView syncPhoto;
	protected TextView syncText;
	protected Messenger mService = null;
	protected boolean isBound = false;
	final Messenger mMessenger = new Messenger(new IncomingHandler());

	protected int INTENT_IMPORT = 1;



	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.gallery);
		toolbar = findViewById(R.id.toolbar);
		toolbar.setTitle(getString(R.string.title_gallery_for_app));
		setSupportActionBar(toolbar);
		FloatingActionButton fab = findViewById(R.id.import_fab);
		fab.setOnClickListener(getImportOnClickListener());
		DrawerLayout drawer = findViewById(R.id.drawer_layout);
		NavigationView navigationView = findViewById(R.id.nav_view);
		ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
		drawer.addDrawerListener(toggle);
		toggle.syncState();
		navigationView.setNavigationItemSelectedListener(this);

		recyclerView = (DragSelectRecyclerView) findViewById(R.id.gallery_recycler_view);
		syncBar = (ViewGroup) findViewById(R.id.syncBar);
		syncProgress = (ProgressBar) findViewById(R.id.syncProgress);
		refreshCProgress = (ProgressBar) findViewById(R.id.refreshCProgress);
		syncPhoto = ((ImageView)findViewById(R.id.syncPhoto));
		syncText = ((TextView)findViewById(R.id.syncText));

		final SwipeRefreshLayout pullToRefresh = findViewById(R.id.pullToRefresh);
		pullToRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
			@Override
			public void onRefresh() {
				sendMessageToSyncService(SyncService.MSG_START_SYNC);
				pullToRefresh.setRefreshing(false);
			}
		});

		((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
		recyclerView.setHasFixedSize(true);
		layoutManager = new AutoFitGridLayoutManager(GalleryActivity.this, Helpers.getThumbSize(GalleryActivity.this));
		layoutManager.setSpanSizeLookup(new AutoFitGridLayoutManager.SpanSizeLookup() {
			@Override
			public int getSpanSize(int position) {
				switch(adapter.getItemViewType(position)){
					case GalleryAdapterPisasso.TYPE_DATE:
						return layoutManager.getCurrentCalcSpanCount();
					default:
						return 1;
				}
			}
		});
		recyclerView.setLayoutManager(layoutManager);
		adapter = new GalleryAdapterPisasso(GalleryActivity.this, GalleryActivity.this, layoutManager, SyncManager.FOLDER_MAIN);
		recyclerView.addOnScrollListener(new HidingScrollListener() {
			@Override
			public void onHide() {
				syncBar.animate().translationY(-syncBar.getHeight()-Helpers.convertDpToPixels(GalleryActivity.this, 20)).setInterpolator(new AccelerateInterpolator(4));
			}


			@Override
			public void onShow() {
				syncBar.animate().translationY(0).setInterpolator(new DecelerateInterpolator(4));
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();

	}

	@Override
	protected void onStop() {
		super.onStop();

	}

	@Override
	protected void onResume() {
		super.onResume();

		LoginManager.checkLogin(this, new LoginManager.UserLogedinCallback() {
			@Override
			public void onUserLoginSuccess() {
				recyclerView.setAdapter(adapter);

			}

			@Override
			public void onUserLoginFail() {
				LoginManager.redirectToLogin(GalleryActivity.this);
			}
		});
		LoginManager.disableLockTimer(this);

		Intent serviceIntent = new Intent(this, SyncService.class);
		try {
			startService(serviceIntent);
			bindService(serviceIntent, mConnection, BIND_AUTO_CREATE);
		}
		catch (IllegalStateException e){}

	}

	@Override
	protected void onPause() {
		super.onPause();
		recyclerView.setAdapter(null);
		LoginManager.setLockedTime(this);

		if (isBound) {
			unbindService(mConnection);
			isBound = false;
		}
	}

	private void sendMessageToSyncService(int type) {
		if (isBound) {
			if (mService != null) {
				try {
					Message msg = Message.obtain(null, type);
					msg.replyTo = mMessenger;
					mService.send(msg);
				}
				catch (RemoteException e) {
				}
			}
		}
	}

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			if(msg.what == SyncService.MSG_RESP_SYNC_STATUS) {
				Bundle bundle = msg.getData();
				int syncStatus = bundle.getInt("syncStatus");
				int totalItemsNumber = bundle.getInt("totalItemsNumber");
				int uploadedFilesCount = bundle.getInt("uploadedFilesCount");
				String currentFile = bundle.getString("currentFile");

				Log.d("message", "syncStatus - " + String.valueOf(syncStatus) + " - " + String.valueOf(totalItemsNumber) + " - " + String.valueOf(uploadedFilesCount) + " - " + currentFile);
				if (syncStatus == SyncService.STATUS_UPLOADING) {
					showSyncBar();
					syncProgress.setVisibility(View.VISIBLE);
					syncProgress.setMax(totalItemsNumber);
					syncProgress.setProgress(uploadedFilesCount);
					syncText.setText(getString(R.string.uploading_file, String.valueOf(uploadedFilesCount), String.valueOf(totalItemsNumber)));
					(new ShowThumbInImageView(GalleryActivity.this, currentFile, syncPhoto)).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
				} else if (syncStatus == SyncService.STATUS_REFRESHING) {
					showSyncBar();
					syncProgress.setVisibility(View.INVISIBLE);
					syncText.setText(getString(R.string.refreshing));
				} else if (syncStatus == SyncService.STATUS_IDLE) {
					hideSyncBar();
				}

			}
			else if(msg.what == SyncService.MSG_SYNC_CURRENT_FILE) {
				Log.d("message", "currentFile");
				Bundle bundle = msg.getData();
				String currentFile = bundle.getString("currentFile");
				(new ShowThumbInImageView(GalleryActivity.this, currentFile, syncPhoto)).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
			}
			else if(msg.what == SyncService.MSG_SYNC_UPLOAD_PROGRESS) {
				Log.d("message", "uploadProgress");
				Bundle bundle = msg.getData();
				int totalItemsNumber = bundle.getInt("totalItemsNumber");
				int uploadedFilesCount = bundle.getInt("uploadedFilesCount");

				syncProgress.setMax(totalItemsNumber);
				syncProgress.setProgress(uploadedFilesCount);
				syncText.setText(getString(R.string.uploading_file, String.valueOf(uploadedFilesCount), String.valueOf(totalItemsNumber)));
			}
			else if(msg.what == SyncService.MSG_SYNC_STATUS_CHANGE) {
				Log.d("message", "syncStatusChange");
				Bundle bundle = msg.getData();
				int newStatus = bundle.getInt("newStatus");
				setSyncStatus(newStatus);
			}
			else if(msg.what == SyncService.MSG_REFRESH_GALLERY) {
				adapter.updateDataSet();
			}
			else{
				super.handleMessage(msg);
			}
		}
	}

	private void showSyncBar(){
		syncBar.setVisibility(View.VISIBLE);
		recyclerView.setPadding(0,Helpers.convertDpToPixels(this, 60),0,0);
		recyclerView.scrollTo(0, 0);
	}
	private void hideSyncBar(){
		syncBar.setVisibility(View.GONE);
		recyclerView.setPadding(0,0,0,0);
	}

	private void setSyncStatus(int syncStatus){
		if (syncStatus == SyncService.STATUS_UPLOADING) {
			showSyncBar();
			refreshCProgress.setVisibility(View.GONE);
			syncPhoto.setVisibility(View.VISIBLE);
			syncProgress.setVisibility(View.VISIBLE);
		} else if (syncStatus == SyncService.STATUS_REFRESHING) {
			showSyncBar();
			refreshCProgress.setVisibility(View.VISIBLE);
			syncPhoto.setVisibility(View.GONE);
			syncProgress.setVisibility(View.INVISIBLE);
			syncText.setText(getString(R.string.refreshing));
		} else if (syncStatus == SyncService.STATUS_IDLE) {
			hideSyncBar();
		}
	}

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			isBound = true;
			mService = new Messenger(service);
			sendMessageToSyncService(SyncService.MSG_REGISTER_CLIENT);
			sendMessageToSyncService(SyncService.MSG_GET_SYNC_STATUS);
		}

		public void onServiceDisconnected(ComponentName className) {
			// This is called when the connection with the service has been unexpectedly disconnected - process crashed.
			mService = null;
			isBound = false;
		}
	};

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		layoutManager.updateAutoFit();
	}

	@Override
	public void onBackPressed() {
		DrawerLayout drawer = findViewById(R.id.drawer_layout);
		if (drawer.isDrawerOpen(GravityCompat.START)) {
			drawer.closeDrawer(GravityCompat.START);
		}
		else if (adapter.isSelectionModeActive()) {
			exitActionMode();
		}
		else {
			super.onBackPressed();
		}
	}



	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.gallery, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		if (id == R.id.action_settings) {
			return true;
		}
		if (id == R.id.action_dashboard) {
			Intent intent = new Intent();
			intent.setClass(this, DashboardActivity.class);
			startActivity(intent);
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@SuppressWarnings("StatementWithEmptyBody")
	@Override
	public boolean onNavigationItemSelected(MenuItem item) {
		// Handle navigation view item clicks here.
		int id = item.getItemId();

		if (id == R.id.nav_gallery) {
			adapter.setFolder(SyncManager.FOLDER_MAIN);
			currentFolder = SyncManager.FOLDER_MAIN;
			toolbar.setTitle(getString(R.string.title_gallery_for_app));
		}
		else if (id == R.id.nav_trash) {
			adapter.setFolder(SyncManager.FOLDER_TRASH);
			currentFolder = SyncManager.FOLDER_TRASH;
			toolbar.setTitle(getString(R.string.title_trash));
		}
		else if (id == R.id.nav_tools) {

		}
		else if (id == R.id.nav_share) {

		}
		else if (id == R.id.nav_send) {

		}

		DrawerLayout drawer = findViewById(R.id.drawer_layout);
		drawer.closeDrawer(GravityCompat.START);
		return true;
	}

	@Override
	public void onClick(int index) {
		if (adapter.isSelectionModeActive()){
			adapter.toggleSelected(index);
		}
		StingleDbFile file = adapter.getStingleFileAtPosition(index);
		Log.d("file", file.filename);
	}

	@Override
	public void onLongClick(int index) {
		recyclerView.setDragSelectActive(true, index);
		if(!adapter.isSelectionModeActive()){
			actionMode = startSupportActionMode(getActionModeCallback());
			onSelectionChanged(1);
		}
		adapter.setSelectionModeActive(true);
	}


	@Override
	public void onSelectionChanged(int count) {
		if(actionMode != null) {
			if(count == 0){
				actionMode.setTitle("");
			}
			else {
				actionMode.setTitle(String.valueOf(count));
			}
		}
	}


	protected ActionMode.Callback getActionModeCallback(){
		return new ActionMode.Callback() {
			@Override
			public boolean onCreateActionMode(ActionMode mode, Menu menu) {
				switch(currentFolder){
					case SyncManager.FOLDER_MAIN:
						mode.getMenuInflater().inflate(R.menu.gallery_action_mode, menu);
						break;
					case SyncManager.FOLDER_TRASH:
						mode.getMenuInflater().inflate(R.menu.gallery_trash_action_mode, menu);
						break;
				}

				return true;
			}

			@Override
			public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
				return true;
			}

			@Override
			public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
				switch(item.getItemId()){
					case R.id.trash :
						trashSelected();
						break;
					case R.id.restore :
						restoreSelected();
						break;
					case R.id.delete :
						deleteSelected();
						break;
				}
				return true;
			}

			@Override
			public void onDestroyActionMode(ActionMode mode) {
				exitActionMode();
				actionMode = null;
			}
		};
	}


	protected void exitActionMode(){
		adapter.clearSelected();
		recyclerView.setDragSelectActive(false, 0);
		if(actionMode != null){
			actionMode.finish();
		}
	}

	protected void trashSelected(){
		final List<Integer> indeces = adapter.getSelectedIndices();
		Helpers.showConfirmDialog(GalleryActivity.this, String.format(getString(R.string.confirm_trash_files), String.valueOf(indeces.size())), new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				final ProgressDialog spinner = Helpers.showProgressDialog(GalleryActivity.this, getString(R.string.trashing_files), null);
				ArrayList<String> filenames = new ArrayList<String>();
				for(Integer index : indeces){
					StingleDbFile file = adapter.getStingleFileAtPosition(index);
					filenames.add(file.filename);
				}

				new SyncManager.MoveToTrashAsyncTask(GalleryActivity.this, filenames, new SyncManager.OnFinish(){
					@Override
					public void onFinish() {
						for(Integer index : indeces) {
							adapter.updateDataSet();
						}
						exitActionMode();
						spinner.dismiss();
					}
				}).execute();
			}
		},
		null);
	}

	protected void restoreSelected(){
		final List<Integer> indeces = adapter.getSelectedIndices();

		final ProgressDialog spinner = Helpers.showProgressDialog(GalleryActivity.this, getString(R.string.restoring_files), null);
		ArrayList<String> filenames = new ArrayList<String>();
		for(Integer index : indeces){
			StingleDbFile file = adapter.getStingleFileAtPosition(index);
			filenames.add(file.filename);
		}

		new SyncManager.RestoreFromTrashAsyncTask(GalleryActivity.this, filenames, new SyncManager.OnFinish(){
			@Override
			public void onFinish() {
				for(Integer index : indeces) {
					adapter.updateDataSet();
				}
				exitActionMode();
				spinner.dismiss();
			}
		}).execute();

	}

	protected void deleteSelected(){
		final List<Integer> indeces = adapter.getSelectedIndices();
		Helpers.showConfirmDialog(GalleryActivity.this, String.format(getString(R.string.confirm_delete_files), String.valueOf(indeces.size())), new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						final ProgressDialog spinner = Helpers.showProgressDialog(GalleryActivity.this, getString(R.string.deleting_files), null);
						ArrayList<String> filenames = new ArrayList<String>();
						for(Integer index : indeces){
							StingleDbFile file = adapter.getStingleFileAtPosition(index);
							filenames.add(file.filename);
						}

						new SyncManager.DeleteFilesAsyncTask(GalleryActivity.this, filenames, new SyncManager.OnFinish(){
							@Override
							public void onFinish() {
								for(Integer index : indeces) {
									adapter.updateDataSet();
								}
								exitActionMode();
								spinner.dismiss();
							}
						}).execute();
					}
				},
				null);
	}

	protected View.OnClickListener getImportOnClickListener(){
		return new View.OnClickListener(){
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
				intent.setType("*/*");
				String[] mimetypes = {"image/*", "video/*"};
				intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
				//intent.addCategory(Intent.CATEGORY_DEFAULT);
				intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
				// Note: This is not documented, but works: Show the Internal Storage menu item in the drawer!
				//intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
				startActivityForResult(Intent.createChooser(intent, "Select photos and videos"), INTENT_IMPORT);
			}
		};
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != Activity.RESULT_OK || data == null) {
			return;
		}
		if(requestCode == INTENT_IMPORT){

			ArrayList<Uri> urisToImport = new ArrayList<Uri>();
			ClipData clipData = data.getClipData();
			if (clipData != null && clipData.getItemCount() > 0) {
				for (int i = 0; i < clipData.getItemCount(); i++) {
					urisToImport.add(clipData.getItemAt(i).getUri());
				}
			}
			else {

				Uri uri = data.getData();
				if (uri != null) {
					urisToImport.add(uri);
				}
			}

			(new FileManager.ImportFilesAsyncTask(this, urisToImport, new FileManager.OnFinish() {
				@Override
				public void onFinish() {
					adapter.updateDataSet();
					sendMessageToSyncService(SyncService.MSG_START_SYNC);
				}
			})).execute();
			/*Uri inputUri = data.getData();
			Log.e("uri", inputUri.getPath());
			ContentResolver resolver = getContentResolver();
			try {

				InputStream is = getContentResolver().openInputStream(inputUri);
				if( is != null ) {
					SafeCameraApplication.getCrypto().importKeyBundle(IOUtils.getInputStreamBytes(is));
					is.close();
					SharedPreferences defaultSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
					defaultSharedPrefs.edit().putBoolean("fingerprint", false).commit();
					LoginManager.logout(this);
					Helpers.showInfoDialog(this, "Import successful");
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (CryptoException e) {
				Helpers.showAlertDialog(this, e.getMessage());
				e.printStackTrace();
			}*/
		}
	}

	public static class ShowThumbInImageView extends AsyncTask<Void, Void, Bitmap> {

		protected Context context;
		protected String filename;
		protected ImageView imageView;

		public ShowThumbInImageView(Context context, String filename, ImageView imageView){
			this.context = context;
			this.filename = filename;
			this.imageView = imageView;
		}

		@Override
		protected Bitmap doInBackground(Void... params) {
			if(filename == null || !LoginManager.isKeyInMemory()){
				return null;
			}
			try {
				File fileToDec = new File(Helpers.getThumbsDir(context) + "/" + filename);
				FileInputStream input = new FileInputStream(fileToDec);
				byte[] decryptedData = SafeCameraApplication.getCrypto().decryptFile(input);

				if (decryptedData != null) {
					return Helpers.decodeBitmap(decryptedData, Helpers.getThumbSize(context));
				}
			} catch (IOException | CryptoException e) {
				e.printStackTrace();
			}
			return null;
		}



		@Override
		protected void onPostExecute(Bitmap bitmap) {
			super.onPostExecute(bitmap);

			if(bitmap != null){
				imageView.setImageBitmap(bitmap);
			}
		}
	}

}
