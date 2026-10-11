"""
Sending email: the sign-up code, the approval with the unique ID, the password reset code, work given out,
and the brochure somebody asked for on the home page.
"""

import logging
import smtplib
from email.message import EmailMessage
from html import escape

from . import pulse
from .config import settings

log = logging.getLogger("deskless.mail")


class MailError(Exception):
    """The email could not be handed to the mail server."""


class Mailer:
    """
    Sends through the SMTP server named in the settings. On a developer's machine that is the test mailbox
    started by Docker, which catches every email and shows it in a browser; on a real server it is the
    organisation's own mail server.
    """

    def send(self, to: str, subject: str, text: str, html: str = "", reply_to: str = "") -> bool:
        """
        True when the email was handed to a mail server; False when there is none to hand it to. With
        [html] the email also carries a laid-out version for mail programs that show one; [reply_to] is
        where an answer to it goes.
        """
        config = settings()
        if not config.smtp_host:
            # No mail server at all: say so in the log rather than lose the message silently.
            log.warning("No SMTP_HOST is set. Email for %s not sent. Subject: %s\n%s", to, subject, text)
            pulse.record("mail", pulse.MAIL, f"e:{to.strip().lower()}", f"would have emailed “{subject}”, but no mail server is set")
            return False
        message = EmailMessage()
        message["From"] = config.mail_from
        message["To"] = to
        message["Subject"] = subject
        message.set_content(text)
        if reply_to:
            message["Reply-To"] = reply_to
        if html:
            message.add_alternative(html, subtype="html")
        try:
            if config.smtp_security == "ssl":
                server = smtplib.SMTP_SSL(config.smtp_host, config.smtp_port, timeout=15)
            else:
                server = smtplib.SMTP(config.smtp_host, config.smtp_port, timeout=15)
            with server:
                if config.smtp_security == "starttls":
                    server.starttls()
                if config.smtp_user:
                    server.login(config.smtp_user, config.smtp_password)
                server.send_message(message)
        except (OSError, smtplib.SMTPException) as error:
            pulse.record("mail", pulse.MAIL, f"e:{to.strip().lower()}", f"the mail server refused “{subject}”: {error}")
            raise MailError(str(error)) from error
        pulse.record("mail", pulse.MAIL, f"e:{to.strip().lower()}", f"emailed “{subject}”")
        return True


def _dashboard() -> str:
    """A line with the dashboard's address, when the server knows where it is reached from outside."""
    address = settings().dashboard_url
    return f"\nDashboard: {address}\n" if address else ""


def code_email(name: str, code: str, minutes: int) -> tuple[str, str]:
    return (
        f"{code} is your DeskLess code",
        f"Hello {name},\n\n"
        f"Your DeskLess code is {code}\n\n"
        f"Type it into the app to confirm this email address. It works for {minutes} minutes.\n"
        "If you did not sign up for DeskLess, you can ignore this email.\n",
    )


def waiting_email(name: str, approver: str) -> tuple[str, str]:
    return (
        "Your DeskLess sign-up is waiting for approval",
        f"Hello {name},\n\n"
        f"Your email address is confirmed. Your sign-up now waits for {approver}.\n"
        "You will get another email with your DeskLess ID as soon as it is approved.\n",
    )


def approved_email(name: str, unique_id: str, role: str = "", invite: str = "") -> tuple[str, str]:
    return (
        f"Your DeskLess ID is {unique_id}",
        f"Hello {name},\n\n"
        f"Your DeskLess account is approved{' as ' + role if role else ''}.\n\n"
        f"Your ID: {unique_id}\n\n"
        "Sign in on the app, or on your dashboard in a browser, with this ID and the password you chose\n"
        "when you signed up.\n"
        + (
            f"\nYour code for the people who report to you: {invite}\n"
            "They type it when they sign up, and their sign-up then comes to you to approve.\n"
            if invite
            else ""
        )
        + _dashboard(),
    )


def rejected_email(name: str, reason: str) -> tuple[str, str]:
    return (
        "Your DeskLess sign-up was not approved",
        f"Hello {name},\n\n"
        "Your DeskLess sign-up was not approved.\n"
        + (f"\nReason: {reason}\n" if reason else "")
        + "\nYou can sign up again with corrected details.\n",
    )


def reset_email(name: str, code: str, minutes: int) -> tuple[str, str]:
    return (
        f"{code} is your DeskLess password reset code",
        f"Hello {name},\n\n"
        f"Your code to set a new DeskLess password is {code}\n\n"
        f"It works for {minutes} minutes. If you did not ask for it, ignore this email: your password is unchanged.\n",
    )


def new_signup_email(name: str, role: str, email: str) -> tuple[str, str]:
    return (
        f"DeskLess: {name} is waiting for approval",
        f"A new sign-up is waiting for you to approve.\n\nName: {name}\nRole: {role}\nEmail: {email}\n\n"
        "Open your DeskLess dashboard to approve or reject it.\n" + _dashboard(),
    )


def assigned_email(name: str, title: str, site: str, due: str, by: str, words: str = "") -> tuple[str, str]:
    return (
        f"DeskLess: an issue for you{' at ' + site if site else ''}",
        f"Hello {name},\n\n"
        f"{by} has given you an issue to fix.\n\n"
        f"Issue: {title}\n"
        + (f"Site: {site}\n" if site else "")
        + (f"Due: {due}\n" if due else "")
        + (f"\n{by} writes:\n{words}\n" if words else "")
        + "\nOpen your DeskLess dashboard to see the picture, and mark it fixed there with a photo of the repair.\n"
        + _dashboard(),
    )


def sent_email(name: str, by: str, title: str, site: str, words: str) -> tuple[str, str]:
    return (
        f"DeskLess: {by} sent you an issue{' at ' + site if site else ''}",
        f"Hello {name},\n\n"
        f"{by} has sent you an issue to look at.\n\n"
        f"Issue: {title}\n"
        + (f"Site: {site}\n" if site else "")
        + f"\n{by} writes:\n{words}\n"
        + "\nOpen your DeskLess dashboard to see it with its picture, and to answer or send it on.\n"
        + _dashboard(),
    )


# ---------------------------------------------------------------- the brochure somebody asked for on the home page

def _points(told: dict) -> list[tuple[str, list[dict]]]:
    return [(heading, told.get(key) or []) for heading, key in (
        ("What DeskLess would do for you", "does"), ("What to know first", "limits"), ("What it does not do", "not"))]


def brochure_email(name: str, told: dict, home: str, deck: str, whatsapp: str, contact: str, phone: str) -> tuple[str, str, str]:
    """
    The brochure, for whoever asked on the home page whether DeskLess fits them: the answer they were given,
    where the brochure, the film and the deck are, and a link straight to WhatsApp for the price. As
    plain text and laid out. Nothing the asker typed is repeated in it except their name.
    """
    place = told.get("place") or {}
    where = place.get("name", "your place")
    hello = f"Hello {name}," if name else "Hello,"
    subject = f"DeskLess for {where}: the brochure you asked for"
    lines = [hello, "", f"Thank you for asking whether DeskLess fits {where}. Here is the answer you were given, and the brochure.", "",
             told["headline"], told["summary"], ""]
    for heading, points in _points(told):
        if points:
            lines += [heading + ":"] + [f"  - {point['says']}" for point in points] + [""]
    lines += [f"The brochure: {home}", f"The film, two minutes: {home}#film"]
    if deck:
        lines.append(f"The pitch deck (PDF): {deck}")
    lines += ["", "For pricing, message us on WhatsApp and we will answer with a price for your place:", whatsapp, "",
              "Team techazsure", f"{contact} · {phone}", "",
              f"You are getting this because this address was typed into the form at {home}"]
    text = "\n".join(lines) + "\n"

    blocks = "".join(
        f'<p style="margin:22px 0 8px;font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#6b7280;font-weight:700">{escape(heading)}</p>'
        + "".join(f'<p style="margin:0 0 8px;padding-left:14px;border-left:3px solid {colour};line-height:1.5">{escape(point["says"])}</p>' for point in points)
        for (heading, points), colour in zip(_points(told), ("#16a34a", "#f59e0b", "#dc2626")) if points
    )
    more = f'<a href="{escape(home)}#film" style="color:#16181d">The film, two minutes</a>' + (
        f' &nbsp;·&nbsp; <a href="{escape(deck)}" style="color:#16181d">The pitch deck (PDF)</a>' if deck else "")
    html = f"""<!doctype html>
<html lang="en"><body style="margin:0;padding:0;background:#f1eee6;font-family:'Segoe UI',Roboto,Arial,sans-serif;color:#16181d;font-size:16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f1eee6"><tr><td align="center" style="padding:24px 12px">
<table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:100%;max-width:600px;background:#ffffff;border-radius:14px;overflow:hidden">
<tr><td style="background:#0f1115;padding:24px 28px">
<span style="display:inline-block;background:#ffc31f;color:#0f1115;font-weight:800;font-size:14px;border-radius:8px;padding:9px 10px">DL</span>
<span style="color:#f3f4f6;font-size:22px;font-weight:700;padding-left:8px">DeskLess</span>
<div style="color:#b3bac6;font-size:13px;padding-top:8px">Inspection reports, without the desk</div></td></tr>
<tr><td style="height:6px;line-height:6px;font-size:0;background:#ffc31f">&nbsp;</td></tr>
<tr><td style="padding:28px">
<p style="margin:0 0 14px">{escape(hello)}</p>
<p style="margin:0 0 20px;line-height:1.55">Thank you for asking whether DeskLess fits {escape(where)}. Here is the answer you were given, and the brochure.</p>
<p style="margin:0 0 8px;font-size:20px;font-weight:700;line-height:1.3">{escape(told["headline"])}</p>
<p style="margin:0;line-height:1.55;color:#374151">{escape(told["summary"])}</p>
{blocks}
<p style="margin:28px 0 12px"><a href="{escape(home)}" style="display:inline-block;background:#ffc31f;color:#0f1115;font-weight:700;text-decoration:none;padding:14px 24px;border-radius:10px">Open the brochure</a></p>
<p style="margin:0 0 26px;font-size:14px;color:#4b5563">{more}</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#ecfdf3;border:1px solid #bbf7d0;border-radius:12px"><tr><td style="padding:20px 22px">
<p style="margin:0 0 6px;font-weight:700;font-size:17px">Pricing</p>
<p style="margin:0 0 16px;line-height:1.5;color:#374151">Message us on WhatsApp and we will answer with a price for your place.</p>
<a href="{escape(whatsapp)}" style="display:inline-block;background:#25d366;color:#06301a;font-weight:700;text-decoration:none;padding:13px 22px;border-radius:10px">Ask for pricing on WhatsApp</a>
</td></tr></table>
</td></tr>
<tr><td style="padding:18px 28px 24px;border-top:1px solid #e5e7eb;font-size:13px;color:#6b7280;line-height:1.5">
<b style="color:#16181d">Team techazsure</b><br>{escape(contact)} &nbsp;·&nbsp; {escape(phone)}<br><br>
You are getting this because this address was typed into the form at <a href="{escape(home)}" style="color:#6b7280">{escape(home)}</a>.</td></tr>
</table></td></tr></table></body></html>
"""
    return subject, text, html


def enquiry_email(name: str, email: str, asked: str, told: dict) -> tuple[str, str]:
    """For the team: somebody asked on the home page whether DeskLess fits them, and wants the brochure."""
    place = (told.get("place") or {}).get("name", "not said")
    lines = [f"{name or 'Somebody'} <{email}> asked on the home page whether DeskLess fits them, and was sent the brochure.", "",
             f"Kind of place: {place}", f"Answer given: {told['headline']}", "", "What they wrote:", asked, ""]
    for heading, points in _points(told):
        if points:
            lines += [heading + ":"] + [f"  - {point['title']}" for point in points] + [""]
    lines.append("Answering this email writes to them.")
    return f"DeskLess enquiry from {email}: {told['headline']}", "\n".join(lines) + "\n"
