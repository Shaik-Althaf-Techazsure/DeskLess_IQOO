"""Sending email: the sign-up code, the approval with the unique ID, the password reset code, work given out."""

import logging
import smtplib
from email.message import EmailMessage

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

    def send(self, to: str, subject: str, text: str) -> bool:
        """True when the email was handed to a mail server; False when there is none to hand it to."""
        config = settings()
        if not config.smtp_host:
            # No mail server at all: say so in the log rather than lose the message silently.
            log.warning("No SMTP_HOST is set. Email for %s not sent. Subject: %s\n%s", to, subject, text)
            return False
        message = EmailMessage()
        message["From"] = config.mail_from
        message["To"] = to
        message["Subject"] = subject
        message.set_content(text)
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
            raise MailError(str(error)) from error
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
