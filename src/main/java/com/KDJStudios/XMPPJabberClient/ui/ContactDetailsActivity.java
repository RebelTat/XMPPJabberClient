package com.KDJStudios.XMPPJabberClient.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import org.openintents.openpgp.util.OpenPgpUtils;

import java.util.List;

import com.KDJStudios.XMPPJabberClient.Config;
import com.KDJStudios.XMPPJabberClient.R;
import com.KDJStudios.XMPPJabberClient.crypto.axolotl.AxolotlService;
import com.KDJStudios.XMPPJabberClient.crypto.axolotl.FingerprintStatus;
import com.KDJStudios.XMPPJabberClient.crypto.axolotl.XmppAxolotlSession;
import com.KDJStudios.XMPPJabberClient.databinding.ActivityContactDetailsBinding;
import com.KDJStudios.XMPPJabberClient.entities.Account;
import com.KDJStudios.XMPPJabberClient.entities.Contact;
import com.KDJStudios.XMPPJabberClient.entities.ListItem;
import com.KDJStudios.XMPPJabberClient.services.XmppConnectionService.OnAccountUpdate;
import com.KDJStudios.XMPPJabberClient.services.XmppConnectionService.OnRosterUpdate;
import com.KDJStudios.XMPPJabberClient.ui.util.MenuDoubleTabUtil;
import com.KDJStudios.XMPPJabberClient.utils.IrregularUnicodeDetector;
import com.KDJStudios.XMPPJabberClient.utils.UIHelper;
import com.KDJStudios.XMPPJabberClient.utils.XmppUri;
import com.KDJStudios.XMPPJabberClient.xml.Namespace;
import com.KDJStudios.XMPPJabberClient.xmpp.OnKeyStatusUpdated;
import com.KDJStudios.XMPPJabberClient.xmpp.OnUpdateBlocklist;
import com.KDJStudios.XMPPJabberClient.xmpp.XmppConnection;
import rocks.xmpp.addr.Jid;

public class ContactDetailsActivity extends OmemoActivity implements OnAccountUpdate, OnRosterUpdate, OnUpdateBlocklist, OnKeyStatusUpdated {
	public static final String ACTION_VIEW_CONTACT = "view_contact";
	ActivityContactDetailsBinding binding;
	private Contact contact;
	private DialogInterface.OnClickListener removeFromRoster = new DialogInterface.OnClickListener() {

		@Override
		public void onClick(DialogInterface dialog, int which) {
			xmppConnectionService.deleteContactOnServer(contact);
		}
	};
	private OnCheckedChangeListener mOnSendCheckedChange = new OnCheckedChangeListener() {

		@Override
		public void onCheckedChanged(CompoundButton buttonView,
				boolean isChecked) {
			if (isChecked) {
				if (contact
						.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
					xmppConnectionService.sendPresencePacket(contact
							.getAccount(),
							xmppConnectionService.getPresenceGenerator()
							.sendPresenceUpdatesTo(contact));
				} else {
					contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
				}
			} else {
				contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
				xmppConnectionService.sendPresencePacket(contact.getAccount(),
						xmppConnectionService.getPresenceGenerator()
						.stopPresenceUpdatesTo(contact));
			}
		}
	};
	private OnCheckedChangeListener mOnReceiveCheckedChange = new OnCheckedChangeListener() {

		@Override
		public void onCheckedChanged(CompoundButton buttonView,
				boolean isChecked) {
			if (isChecked) {
				xmppConnectionService.sendPresencePacket(contact.getAccount(),
						xmppConnectionService.getPresenceGenerator()
						.requestPresenceUpdatesFrom(contact));
			} else {
				xmppConnectionService.sendPresencePacket(contact.getAccount(),
						xmppConnectionService.getPresenceGenerator()
						.stopPresenceUpdatesFrom(contact));
			}
		}
	};
	private Jid accountJid;
	private Jid contactJid;
	private boolean showDynamicTags = false;
	private boolean showLastSeen = false;
	private boolean showInactiveOmemo = false;
	private String messageFingerprint;

	private DialogInterface.OnClickListener addToPhonebook = new DialogInterface.OnClickListener() {

		@Override
		public void onClick(DialogInterface dialog, int which) {
			Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
			intent.setType(Contacts.CONTENT_ITEM_TYPE);
			intent.putExtra(Intents.Insert.IM_HANDLE, contact.getJid().toString());
			intent.putExtra(Intents.Insert.IM_PROTOCOL,
					CommonDataKinds.Im.PROTOCOL_JABBER);
			intent.putExtra("finishActivityOnSaveCompleted", true);
			ContactDetailsActivity.this.startActivityForResult(intent, 0);
		}
	};

	private OnClickListener onBadgeClick = new OnClickListener() {

		@Override
		public void onClick(View v) {
			Uri systemAccount = contact.getSystemAccount();
			if (systemAccount == null) {
				AlertDialog.Builder builder = new AlertDialog.Builder(
						ContactDetailsActivity.this);
				builder.setTitle(getString(R.string.action_add_phone_book));
				builder.setMessage(getString(R.string.add_phone_book_text, contact.getJid().toString()));
				builder.setNegativeButton(getString(R.string.cancel), null);
				builder.setPositiveButton(getString(R.string.add), addToPhonebook);
				builder.create().show();
			} else {
				Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setData(systemAccount);
				startActivity(intent);
			}
		}
	};

	@Override
	public void onRosterUpdate() {
		refreshUi();
	}

	@Override
	public void onAccountUpdate() {
		refreshUi();
	}

	@Override
	public void OnUpdateBlocklist(final Status status) {
		refreshUi();
	}

	@Override
	protected void refreshUiReal() {
		invalidateOptionsMenu();
		populateView();
	}

	@Override
	protected String getShareableUri(boolean http) {
		final String prefix = http ? "https://conversations.im/i/" : "xmpp:";
		if (contact != null) {
			return prefix+contact.getJid().asBareJid().toEscapedString();
		} else {
			return "";
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		showInactiveOmemo = savedInstanceState != null && savedInstanceState.getBoolean("show_inactive_omemo",false);
		if (getIntent().getAction().equals(ACTION_VIEW_CONTACT)) {
			try {
				this.accountJid = Jid.of(getIntent().getExtras().getString(EXTRA_ACCOUNT));
			} catch (final IllegalArgumentException ignored) {
			}
			try {
				this.contactJid = Jid.of(getIntent().getExtras().getString("contact"));
			} catch (final IllegalArgumentException ignored) {
			}
		}
		this.messageFingerprint = getIntent().getStringExtra("fingerprint");
		this.binding = DataBindingUtil.setContentView(this, R.layout.activity_contact_details);

		setSupportActionBar((Toolbar) binding.toolbar);
		configureActionBar(getSupportActionBar());
		binding.showInactiveDevices.setOnClickListener(v -> {
			showInactiveOmemo = !showInactiveOmemo;
			populateView();
		});
		binding.addContactButton.setOnClickListener(v -> showAddToRosterDialog(contact));
	}

	@Override
	public void onSaveInstanceState(final Bundle savedInstanceState) {
		savedInstanceState.putBoolean("show_inactive_omemo",showInactiveOmemo);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onStart() {
		super.onStart();
		final int theme = findTheme();
		if (this.mTheme != theme) {
			recreate();
		} else {
			final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
			this.showDynamicTags = preferences.getBoolean(SettingsActivity.SHOW_DYNAMIC_TAGS, false);
			this.showLastSeen = preferences.getBoolean("last_activity", false);
		}
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem menuItem) {
		if (MenuDoubleTabUtil.shouldIgnoreTap()) {
			return false;
		}
		final AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setNegativeButton(getString(R.string.cancel), null);
		switch (menuItem.getItemId()) {
			case android.R.id.home:
				finish();
				break;
			case R.id.action_share_http:
				shareLink(true);
				break;
			case R.id.action_share_uri:
				shareLink(false);
				break;
			case R.id.action_delete_contact:
				builder.setTitle(getString(R.string.action_delete_contact))
					.setMessage(getString(R.string.remove_contact_text, contact.getJid().toString()))
					.setPositiveButton(getString(R.string.delete),
							removeFromRoster).create().show();
				break;
			case R.id.action_edit_contact:
				Uri systemAccount = contact.getSystemAccount();
				if (systemAccount == null) {
					quickEdit(contact.getDisplayName(), 0, new OnValueEdited() {

						@Override
						public String onValueEdited(String value) {
							contact.setServerName(value);
							ContactDetailsActivity.this.xmppConnectionService.pushContactToServer(contact);
							populateView();
							return null;
						}
					});
				} else {
					Intent intent = new Intent(Intent.ACTION_EDIT);
					intent.setDataAndType(systemAccount, Contacts.CONTENT_ITEM_TYPE);
					intent.putExtra("finishActivityOnSaveCompleted", true);
					startActivity(intent);
				}
				break;
			case R.id.action_block:
				BlockContactDialog.show(this, contact);
				break;
			case R.id.action_unblock:
				BlockContactDialog.show(this, contact);
				break;
		}
		return super.onOptionsItemSelected(menuItem);
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.contact_details, menu);
		MenuItem block = menu.findItem(R.id.action_block);
		MenuItem unblock = menu.findItem(R.id.action_unblock);
		MenuItem edit = menu.findItem(R.id.action_edit_contact);
		MenuItem delete = menu.findItem(R.id.action_delete_contact);
		if (contact == null) {
			return true;
		}
		final XmppConnection connection = contact.getAccount().getXmppConnection();
		if (connection != null && connection.getFeatures().blocking()) {
			if (this.contact.isBlocked()) {
				block.setVisible(false);
			} else {
				unblock.setVisible(false);
			}
		} else {
			unblock.setVisible(false);
			block.setVisible(false);
		}
		if (!contact.showInRoster()) {
			edit.setVisible(false);
			delete.setVisible(false);
		}
		return super.onCreateOptionsMenu(menu);
	}

	private void populateView() {
		if (contact == null) {
			return;
		}
		invalidateOptionsMenu();
		setTitle(contact.getDisplayName());
		if (contact.showInRoster()) {
			binding.detailsSendPresence.setVisibility(View.VISIBLE);
			binding.detailsReceivePresence.setVisibility(View.VISIBLE);
			binding.addContactButton.setVisibility(View.GONE);
			binding.detailsSendPresence.setOnCheckedChangeListener(null);
			binding.detailsReceivePresence.setOnCheckedChangeListener(null);

			List<String> statusMessages = contact.getPresences().getStatusMessages();
			if (statusMessages.size() == 0) {
				binding.statusMessage.setVisibility(View.GONE);
			} else {
				StringBuilder builder = new StringBuilder();
				binding.statusMessage.setVisibility(View.VISIBLE);
				int s = statusMessages.size();
				for(int i = 0; i < s; ++i) {
					if (s > 1) {
						builder.append("• ");
					}
					builder.append(statusMessages.get(i));
					if (i < s - 1) {
						builder.append("\n");
					}
				}
				binding.statusMessage.setText(builder);
			}

			if (contact.getOption(Contact.Options.FROM)) {
				binding.detailsSendPresence.setText(R.string.send_presence_updates);
				binding.detailsSendPresence.setChecked(true);
			} else if (contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
				binding.detailsSendPresence.setChecked(false);
				binding.detailsSendPresence.setText(R.string.send_presence_updates);
			} else {
				binding.detailsSendPresence.setText(R.string.preemptively_grant);
				if (contact.getOption(Contact.Options.PREEMPTIVE_GRANT)) {
					binding.detailsSendPresence.setChecked(true);
				} else {
					binding.detailsSendPresence.setChecked(false);
				}
			}
			if (contact.getOption(Contact.Options.TO)) {
				binding.detailsReceivePresence.setText(R.string.receive_presence_updates);
				binding.detailsReceivePresence.setChecked(true);
			} else {
				binding.detailsReceivePresence.setText(R.string.ask_for_presence_updates);
				if (contact.getOption(Contact.Options.ASKING)) {
					binding.detailsReceivePresence.setChecked(true);
				} else {
					binding.detailsReceivePresence.setChecked(false);
				}
			}
			if (contact.getAccount().isOnlineAndConnected()) {
				binding.detailsReceivePresence.setEnabled(true);
				binding.detailsSendPresence.setEnabled(true);
			} else {
				binding.detailsReceivePresence.setEnabled(false);
				binding.detailsSendPresence.setEnabled(false);
			}
			binding.detailsSendPresence.setOnCheckedChangeListener(this.mOnSendCheckedChange);
			binding.detailsReceivePresence.setOnCheckedChangeListener(this.mOnReceiveCheckedChange);
		} else {
			binding.addContactButton.setVisibility(View.VISIBLE);
			binding.detailsSendPresence.setVisibility(View.GONE);
			binding.detailsReceivePresence.setVisibility(View.GONE);
			binding.statusMessage.setVisibility(View.GONE);
		}

		if (contact.isBlocked() && !this.showDynamicTags) {
			binding.detailsLastseen.setVisibility(View.VISIBLE);
			binding.detailsLastseen.setText(R.string.contact_blocked);
		} else {
			if (showLastSeen
					&& contact.getLastseen() > 0
					&& contact.getPresences().allOrNonSupport(Namespace.IDLE)) {
				binding.detailsLastseen.setVisibility(View.VISIBLE);
				binding.detailsLastseen.setText(UIHelper.lastseen(getApplicationContext(), contact.isActive(), contact.getLastseen()));
			} else {
				binding.detailsLastseen.setVisibility(View.GONE);
			}
		}

		binding.detailsContactjid.setText(IrregularUnicodeDetector.style(this,contact.getJid()));
		String account;
		if (Config.DOMAIN_LOCK != null) {
			account = contact.getAccount().getJid().getLocal();
		} else {
			account = contact.getAccount().getJid().asBareJid().toString();
		}
		binding.detailsAccount.setText(getString(R.string.using_account, account));
		binding.detailsContactBadge.setImageBitmap(avatarService().get(contact, getPixel(72)));
		binding.detailsContactBadge.setOnClickListener(this.onBadgeClick);

		binding.detailsContactKeys.removeAllViews();
		boolean hasKeys = false;
		final LayoutInflater inflater = getLayoutInflater();
		final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
		if (Config.supportOmemo() && axolotlService != null) {
			boolean skippedInactive = false;
			boolean showsInactive = false;
			for (final XmppAxolotlSession session : axolotlService.findSessionsForContact(contact)) {
				final FingerprintStatus trust = session.getTrust();
				hasKeys |= !trust.isCompromised();
				if (!trust.isActive()) {
					if (showInactiveOmemo) {
						showsInactive = true;
					} else {
						skippedInactive = true;
						continue;
					}
				}
				if (!trust.isCompromised()) {
					boolean highlight = session.getFingerprint().equals(messageFingerprint);
					addFingerprintRow(binding.detailsContactKeys, session, highlight);
				}
			}
			if (showsInactive || skippedInactive) {
				binding.showInactiveDevices.setText(showsInactive ? R.string.hide_inactive_devices : R.string.show_inactive_devices);
				binding.showInactiveDevices.setVisibility(View.VISIBLE);
			} else {
				binding.showInactiveDevices.setVisibility(View.GONE);
			}
		} else {
			binding.showInactiveDevices.setVisibility(View.GONE);
		}
		binding.scanButton.setVisibility(hasKeys && isCameraFeatureAvailable() ? View.VISIBLE : View.GONE);
		if (hasKeys) {
			binding.scanButton.setOnClickListener((v)-> ScanActivity.scan(this));
		}
		if (Config.supportOpenPgp() && contact.getPgpKeyId() != 0) {
			hasKeys = true;
			View view = inflater.inflate(R.layout.contact_key, binding.detailsContactKeys, false);
			TextView key = (TextView) view.findViewById(R.id.key);
			TextView keyType = (TextView) view.findViewById(R.id.key_type);
			keyType.setText(R.string.openpgp_key_id);
			if ("pgp".equals(messageFingerprint)) {
				keyType.setTextColor(ContextCompat.getColor(this, R.color.accent));
			}
			key.setText(OpenPgpUtils.convertKeyIdToHex(contact.getPgpKeyId()));
			final OnClickListener openKey = v -> launchOpenKeyChain(contact.getPgpKeyId());
			view.setOnClickListener(openKey);
			key.setOnClickListener(openKey);
			keyType.setOnClickListener(openKey);
			binding.detailsContactKeys.addView(view);
		}
		binding.keysWrapper.setVisibility(hasKeys ? View.VISIBLE : View.GONE);

		List<ListItem.Tag> tagList = contact.getTags(this);
		if (tagList.size() == 0 || !this.showDynamicTags) {
			binding.tags.setVisibility(View.GONE);
		} else {
			binding.tags.setVisibility(View.VISIBLE);
			binding.tags.removeAllViewsInLayout();
			for(final ListItem.Tag tag : tagList) {
				final TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag,binding.tags,false);
				tv.setText(tag.getName());
				tv.setBackgroundColor(tag.getColor());
				binding.tags.addView(tv);
			}
		}
	}

	public void onBackendConnected() {
		if (accountJid != null && contactJid != null) {
			Account account = xmppConnectionService.findAccountByJid(accountJid);
			if (account == null) {
				return;
			}
			this.contact = account.getRoster().getContact(contactJid);
			if (mPendingFingerprintVerificationUri != null) {
				processFingerprintVerification(mPendingFingerprintVerificationUri);
				mPendingFingerprintVerificationUri = null;
			}
			populateView();
		}
	}

	@Override
	public void onKeyStatusUpdated(AxolotlService.FetchStatus report) {
		refreshUi();
	}

	@Override
	protected void processFingerprintVerification(XmppUri uri) {
		if (contact != null && contact.getJid().asBareJid().equals(uri.getJid()) && uri.hasFingerprints()) {
			if (xmppConnectionService.verifyFingerprints(contact,uri.getFingerprints())) {
				Toast.makeText(this,R.string.verified_fingerprints,Toast.LENGTH_SHORT).show();
			}
		} else {
			Toast.makeText(this,R.string.invalid_barcode,Toast.LENGTH_SHORT).show();
		}
	}
}
