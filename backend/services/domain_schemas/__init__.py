"""
Domain schemas for ZeroTouch Action Converter.

Each domain (knowledge_worker, restaurant, education, ...) declares its own
intent catalog and payload schemas. The Action Converter looks up the schema
for a given domain to know what intents to extract and how to validate them.
"""

from services.domain_schemas import knowledge_worker

DOMAIN_REGISTRY = {
    knowledge_worker.DOMAIN: knowledge_worker,
}


def get_domain(domain_name: str):
    return DOMAIN_REGISTRY.get(domain_name)
