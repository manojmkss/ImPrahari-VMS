"""The ImPrahari integration."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PASSWORD, CONF_PORT, CONF_USERNAME, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import ImPrahariApiClient
from .const import (
    CONF_ACCESS_TOKEN,
    CONF_FAST_POLL_INTERVAL,
    CONF_REFRESH_TOKEN,
    CONF_SLOW_POLL_INTERVAL,
    CONF_VERIFY_SSL,
    DEFAULT_FAST_POLL_SECONDS,
    DEFAULT_SLOW_POLL_SECONDS,
    DEFAULT_VERIFY_SSL,
)
from .coordinator import ImPrahariCameraCoordinator, ImPrahariSystemCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.ALARM_CONTROL_PANEL, Platform.CAMERA, Platform.BINARY_SENSOR, Platform.SENSOR]


@dataclass
class ImPrahariData:
    api: ImPrahariApiClient
    camera_coordinator: ImPrahariCameraCoordinator
    system_coordinator: ImPrahariSystemCoordinator


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    verify_ssl = entry.options.get(CONF_VERIFY_SSL, entry.data.get(CONF_VERIFY_SSL, DEFAULT_VERIFY_SSL))
    session = async_get_clientsession(hass, verify_ssl=verify_ssl)

    api = ImPrahariApiClient(
        session,
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_USERNAME],
        entry.data[CONF_PASSWORD],
        access_token=entry.data.get(CONF_ACCESS_TOKEN),
        refresh_token=entry.data.get(CONF_REFRESH_TOKEN),
    )

    async def _persist_tokens(access_token: str, refresh_token: str) -> None:
        # Refresh rotates the refresh token on every call - persisting
        # immediately means a later HA restart (before the next natural
        # refresh) reuses a still-valid token instead of an already-spent one.
        hass.config_entries.async_update_entry(
            entry,
            data={**entry.data, CONF_ACCESS_TOKEN: access_token, CONF_REFRESH_TOKEN: refresh_token},
        )

    api.on_tokens_updated = _persist_tokens

    fast_seconds = entry.options.get(CONF_FAST_POLL_INTERVAL, DEFAULT_FAST_POLL_SECONDS)
    slow_seconds = entry.options.get(CONF_SLOW_POLL_INTERVAL, DEFAULT_SLOW_POLL_SECONDS)

    camera_coordinator = ImPrahariCameraCoordinator(hass, entry, api, fast_seconds)
    system_coordinator = ImPrahariSystemCoordinator(hass, entry, api, slow_seconds)

    # Raises ConfigEntryAuthFailed / ConfigEntryNotReady as appropriate on
    # failure - HA handles both (starts reauth, or retries setup later).
    await camera_coordinator.async_config_entry_first_refresh()
    await system_coordinator.async_config_entry_first_refresh()

    entry.runtime_data = ImPrahariData(
        api=api, camera_coordinator=camera_coordinator, system_coordinator=system_coordinator
    )

    entry.async_on_unload(entry.add_update_listener(_async_reload_entry))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def _async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Options (poll intervals, verify_ssl) changed - reload to apply them."""
    await hass.config_entries.async_reload(entry.entry_id)
